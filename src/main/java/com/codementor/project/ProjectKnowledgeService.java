package com.codementor.project;

import com.codementor.privacy.FileExclusionPolicy;
import com.codementor.review.ReviewCoordinator;
import com.codementor.settings.CodeMentorProjectConsent;
import com.codementor.settings.CodeMentorProjectConsent.ProjectScanDecision;
import com.codementor.settings.CodeMentorSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 项目画像的生命周期：授权、扫描、记忆（内存 + 本机文件）、过期刷新与遗忘。
 *
 * <p>规则：
 * <ul>
 *   <li>没有 {@link ProjectScanDecision#GRANTED} 时不扫描、不读取已保存的画像，
 *       {@link #profileForRequest()} 恒返回空画像——插件退回“只看当前文件”。</li>
 *   <li>扫描在可取消的非阻塞读操作里进行，等索引就绪后才开始，不阻塞编辑。</li>
 *   <li>请求不等待刷新：已有画像时立即使用旧画像并在后台刷新；
 *       只有首次扫描会让请求最多等待 {@value #FIRST_SCAN_WAIT_SECONDS} 秒。</li>
 *   <li>撤销授权会同时清空内存画像并删除本机文件。</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class ProjectKnowledgeService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ProjectKnowledgeService.class);

    /** 首次扫描时一次请求最多等待的时间；超时就先不带画像，扫描继续在后台完成。 */
    static final long FIRST_SCAN_WAIT_SECONDS = 8L;
    /** 两次自动刷新的最小间隔，避免连续请求反复扫描。 */
    private static final long MIN_REFRESH_INTERVAL_MILLIS = 30_000L;

    public enum Status {
        /** 还没有问过用户。 */
        NOT_ASKED,
        /** 用户选择不允许读取整个项目。 */
        DENIED,
        /** 已授权，正在读取项目。 */
        SCANNING,
        /** 已授权，但还没有可用画像（例如项目里没有 Java 源码）。 */
        EMPTY,
        /** 已授权且画像可用。 */
        READY
    }

    public interface Listener {
        /** 授权或画像变化时回调；可能来自任意线程，监听方自行切换到 EDT。 */
        void onProjectKnowledgeChanged();
    }

    private final Project project;
    private final ProjectProfileStore store;
    private final ExecutorService ioExecutor;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private volatile ProjectProfile profile = ProjectProfile.empty();
    private boolean diskChecked;
    private Future<?> runningScan;
    private CompletableFuture<ProjectProfile> runningResult;
    private long lastScanStartedAtMillis;
    private long scannedModificationCount = -1L;
    /** 本次 IDE 会话里用户已关闭过授权询问（未作决定）；不持久化，重启后可以再问一次。 */
    private volatile boolean promptDismissedThisSession;

    public ProjectKnowledgeService(Project project) {
        this.project = project;
        this.store = new ProjectProfileStore(
                Path.of(PathManager.getSystemPath(), "codementor", "project-profiles"));
        this.ioExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
                "CodeMentor Project Profile", 1);
    }

    public static ProjectKnowledgeService getInstance(Project project) {
        return project.getService(ProjectKnowledgeService.class);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public ProjectScanDecision decision() {
        return consent().getProjectScanDecision();
    }

    /**
     * 是否应该主动弹出授权询问：只在还没有决定、且本次 IDE 会话里没有被关掉过时为 true，
     * 避免用户不想现在决定时，每次请求都被同一个对话框打断。
     */
    public boolean shouldPromptForDecision() {
        return !project.isDisposed()
                && !promptDismissedThisSession
                && decision() == ProjectScanDecision.UNDECIDED;
    }

    /** 用户关闭了授权询问而没有选择；本次 IDE 会话不再主动询问，仍可从侧栏手动开启。 */
    public void promptDismissed() {
        promptDismissedThisSession = true;
    }

    public Status status() {
        // 项目关闭过程中服务容器可能已销毁，不能再查找授权服务。
        if (project.isDisposed()) return Status.NOT_ASKED;
        ProjectScanDecision decision = decision();
        if (decision == ProjectScanDecision.UNDECIDED) return Status.NOT_ASKED;
        if (decision == ProjectScanDecision.DENIED) return Status.DENIED;
        if (isScanning()) return Status.SCANNING;
        return profile.isEmpty() ? Status.EMPTY : Status.READY;
    }

    /** 侧栏状态行使用的一句话说明。 */
    public String statusText() {
        return switch (status()) {
            case NOT_ASKED, DENIED -> "项目画像：未开启（导师只看当前文件）";
            case SCANNING -> profile.isEmpty()
                    ? "项目画像：正在读取项目…"
                    : "项目画像：已记住 " + profile.classes().size() + " 个类型，正在刷新…";
            case EMPTY -> "项目画像：已开启，暂无可用内容";
            case READY -> "项目画像：已记住 " + profile.classes().size() + " 个类型";
        };
    }

    /** 当前内存中的画像；未授权时恒为空。 */
    public ProjectProfile currentProfile() {
        if (project.isDisposed()) return ProjectProfile.empty();
        return consent().isProjectScanGranted()
                ? profile.filtered(new FileExclusionPolicy(CodeMentorSettings.getInstance().getExcludedFilePatterns()))
                : ProjectProfile.empty();
    }

    /** 用户同意读取整个项目：记录决定并立即开始首次扫描。 */
    public void grant() {
        consent().setProjectScanDecision(ProjectScanDecision.GRANTED);
        fireChanged();
        startScan();
    }

    /** 用户拒绝或撤销：记录决定，清空内存画像并删除本机文件。 */
    public void deny() {
        boolean wasGranted = consent().isProjectScanGranted();
        consent().setProjectScanDecision(ProjectScanDecision.DENIED);
        forget();
        if (wasGranted) ReviewCoordinator.getInstance(project).cancelAll();
    }

    /**
     * 侧栏打开时调用：已授权就把上次会话记住的画像读回内存并在后台刷新，
     * 这样重启 IDE 后导师仍然“记得”项目。未授权时不做任何事。
     */
    public void warmUp() {
        if (!project.isDisposed() && consent().isProjectScanGranted()) {
            profileForRequest();
        }
    }

    /** 手动“重新扫描项目”。未授权时不做任何事。 */
    public CompletableFuture<ProjectProfile> rescan() {
        return startScan();
    }

    /**
     * 供一次导师请求使用的画像。永不抛出异常；未授权、失败或首次扫描超时都返回空画像，
     * 请求照常以“只看当前文件”的方式继续。
     */
    public CompletableFuture<ProjectProfile> profileForRequest() {
        if (project.isDisposed() || !consent().isProjectScanGranted()) {
            return CompletableFuture.completedFuture(ProjectProfile.empty());
        }
        ProjectProfile current = profile;
        if (!current.isEmpty()) {
            if (isStale()) startScan();
            return CompletableFuture.completedFuture(current);
        }
        return CompletableFuture.supplyAsync(this::loadFromDiskOnce, ioExecutor)
                .thenCompose(loaded -> {
                    if (!loaded.isEmpty()) {
                        // 上次会话记住的画像先用着，后台刷新给下一次请求。
                        startScan();
                        return CompletableFuture.completedFuture(loaded);
                    }
                    // thenApply 生成独立副本：超时只结束本次等待，不影响共享的扫描结果。
                    return startScan()
                            .thenApply(scanned -> scanned)
                            .completeOnTimeout(
                                    ProjectProfile.empty(),
                                    FIRST_SCAN_WAIT_SECONDS,
                                    TimeUnit.SECONDS);
                })
                .exceptionally(error -> ProjectProfile.empty());
    }

    private ProjectProfile loadFromDiskOnce() {
        synchronized (lock) {
            if (diskChecked) return profile;
            diskChecked = true;
        }
        try {
            ProjectProfile loaded = store.load(projectKey());
            if (loaded != null && !loaded.isEmpty() && consent().isProjectScanGranted()) {
                synchronized (lock) {
                    if (profile.isEmpty()) profile = loaded;
                }
                fireChanged();
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("读取本机项目画像失败，将重新扫描", e);
        }
        return profile;
    }

    private boolean isStale() {
        synchronized (lock) {
            if (isScanningLocked()) return false;
            if (System.currentTimeMillis() - lastScanStartedAtMillis
                    < MIN_REFRESH_INTERVAL_MILLIS) {
                return false;
            }
            return modificationCount() != scannedModificationCount;
        }
    }

    private CompletableFuture<ProjectProfile> startScan() {
        CompletableFuture<ProjectProfile> result;
        synchronized (lock) {
            if (project.isDisposed() || !consent().isProjectScanGranted()) {
                return CompletableFuture.completedFuture(ProjectProfile.empty());
            }
            if (isScanningLocked() && runningResult != null) {
                return runningResult;
            }
            result = new CompletableFuture<>();
            long startedModificationCount = modificationCount();
            FileExclusionPolicy policy = new FileExclusionPolicy(
                    CodeMentorSettings.getInstance().getExcludedFilePatterns());
            lastScanStartedAtMillis = System.currentTimeMillis();
            runningResult = result;
            CompletableFuture<ProjectProfile> pending = result;
            runningScan = ReadAction.<ProjectProfile>nonBlocking(() -> {
                        try {
                            return ProjectScanner.scan(project, policy);
                        } catch (ProcessCanceledException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            LOG.warn("项目画像扫描失败", e);
                            return null;
                        }
                    })
                    .inSmartMode(project)
                    .expireWith(this)
                    .finishOnUiThread(
                            ModalityState.any(),
                            scanned -> onScanFinished(
                                    pending, scanned, startedModificationCount))
                    .submit(AppExecutorUtil.getAppExecutorService());
        }
        fireChanged();
        return result;
    }

    private void onScanFinished(
            CompletableFuture<ProjectProfile> result,
            ProjectProfile scanned,
            long startedModificationCount) {
        ProjectProfile accepted;
        synchronized (lock) {
            if (runningResult != result) return;
            runningResult = null;
            runningScan = null;
            // 扫描期间授权可能已被撤销；撤销后的结果必须丢弃，不能复活画像。
            if (scanned == null || !consent().isProjectScanGranted()) {
                accepted = null;
            } else {
                profile = scanned;
                diskChecked = true;
                scannedModificationCount = startedModificationCount;
                accepted = scanned;
            }
        }
        result.complete(accepted == null ? ProjectProfile.empty() : accepted);
        fireChanged();
        if (accepted == null) return;
        ProjectProfile toSave = accepted;
        ioExecutor.execute(() -> {
            try {
                if (!consent().isProjectScanGranted()) return;
                if (toSave.isEmpty()) {
                    store.delete(projectKey());
                } else {
                    store.save(projectKey(), toSave);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("保存本机项目画像失败", e);
            }
        });
    }

    private void forget() {
        synchronized (lock) {
            if (runningScan != null) runningScan.cancel(true);
            runningScan = null;
            if (runningResult != null) runningResult.complete(ProjectProfile.empty());
            runningResult = null;
            profile = ProjectProfile.empty();
            diskChecked = true;
            scannedModificationCount = -1L;
        }
        fireChanged();
        ioExecutor.execute(() -> {
            try {
                store.delete(projectKey());
            } catch (IOException | RuntimeException e) {
                LOG.warn("删除本机项目画像失败", e);
            }
        });
    }

    private boolean isScanning() {
        synchronized (lock) {
            return isScanningLocked();
        }
    }

    private boolean isScanningLocked() {
        return runningScan != null && !runningScan.isDone();
    }

    private long modificationCount() {
        return PsiManager.getInstance(project).getModificationTracker().getModificationCount();
    }

    private CodeMentorProjectConsent consent() {
        return CodeMentorProjectConsent.getInstance(project);
    }

    /** 只用于生成本机文件名的摘要输入，不会以明文保存或发送。 */
    private String projectKey() {
        String basePath = project.getBasePath();
        return basePath == null || basePath.isBlank() ? project.getName() : basePath;
    }

    private void fireChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onProjectKnowledgeChanged();
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.warn("项目画像监听器回调异常", e);
            }
        }
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            if (runningScan != null) runningScan.cancel(true);
            runningScan = null;
            if (runningResult != null) runningResult.complete(ProjectProfile.empty());
            runningResult = null;
        }
        listeners.clear();
        ioExecutor.shutdown();
    }
}
