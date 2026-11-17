package com.codementor.ui;

import com.codementor.project.ProjectKnowledgeFormatter;
import com.codementor.project.ProjectKnowledgeService;
import com.codementor.settings.CodeMentorProjectConsent.ProjectScanDecision;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Dimension;

/** “读取整个项目”授权与项目画像管理的全部对话框。 */
public final class ProjectKnowledgeDialogs {

    private static final String TITLE = "CodeMentor 项目画像";

    private static final String CONSENT_MESSAGE =
            "为了像老师一样结合你的项目来讲，CodeMentor 想读取当前项目的全部 Java 源码，"
                    + "并在本机记住项目的分层结构、类与方法签名和常见写法（项目画像）。\n\n"
                    + "• 读取只在本机进行，不会上传源码文件；\n"
                    + "• 只记住类名、注解、字段类型和方法签名，不保存方法体、注释和字符串；\n"
                    + "• 讲解时只把一段有长度上限的画像摘要随请求发送，可在“查看发送预览”里逐字核对；\n"
                    + "• 命中排除规则的文件（.env、密钥等）和项目目录外的文件不会读取；\n"
                    + "• 可随时在“更多… → 项目画像…”里重新扫描、查看或撤销并删除。\n\n"
                    + "不允许也能正常使用，导师只会看到当前方法及所在文件的有限上下文。";

    private ProjectKnowledgeDialogs() {}

    /**
     * 还没问过时询问一次。选择“允许”或“不允许”后不再询问；直接关闭对话框视为暂未决定，
     * 本次 IDE 会话内不再主动打断，重启 IDE 后的首次手动请求再问，
     * 期间可随时从侧栏“项目画像…”开启。无界面和测试环境下不弹窗。
     */
    public static void askOnce(Project project) {
        if (project.isDisposed()) return;
        ProjectKnowledgeService service = ProjectKnowledgeService.getInstance(project);
        if (!service.shouldPromptForDecision()) return;
        if (ApplicationManager.getApplication().isUnitTestMode()
                || ApplicationManager.getApplication().isHeadlessEnvironment()) {
            return;
        }
        int choice = showConsent(project);
        if (choice != 0 && choice != 1) {
            service.promptDismissed();
        }
        applyConsentChoice(service, choice);
    }

    /** 侧栏“项目画像…”入口：按当前状态给出对应操作。 */
    public static void manage(Project project) {
        if (project.isDisposed()) return;
        ProjectKnowledgeService service = ProjectKnowledgeService.getInstance(project);
        if (service.decision() != ProjectScanDecision.GRANTED) {
            applyConsentChoice(service, showConsent(project));
            return;
        }
        int choice = Messages.showDialog(
                project,
                service.statusText() + "。\n\n"
                        + "画像只保存在本机 IDE 系统目录，不写入项目，也不会被提交到版本库。",
                TITLE,
                new String[] {"查看画像内容", "重新扫描项目", "撤销并删除画像", "关闭"},
                3,
                Messages.getInformationIcon());
        if (choice == 0) {
            new OverviewDialog(
                    project, ProjectKnowledgeFormatter.overview(service.currentProfile())).show();
        } else if (choice == 1) {
            service.rescan();
        } else if (choice == 2) {
            service.deny();
            Messages.showInfoMessage(
                    project,
                    "已撤销读取授权并删除本机保存的项目画像。之后导师只看当前文件；"
                            + "需要时可在“更多… → 项目画像…”里重新开启。",
                    TITLE);
        }
    }

    private static int showConsent(Project project) {
        return Messages.showDialog(
                project,
                CONSENT_MESSAGE,
                "允许 CodeMentor 读取整个项目吗？",
                new String[] {"允许并开始读取", "不允许"},
                0,
                Messages.getQuestionIcon());
    }

    private static void applyConsentChoice(ProjectKnowledgeService service, int choice) {
        if (choice == 0) {
            service.grant();
        } else if (choice == 1) {
            service.deny();
        }
    }

    /** 只读、可滚动的画像概览；内容来自本机，不触发任何网络请求。 */
    private static final class OverviewDialog extends DialogWrapper {

        private final String text;

        private OverviewDialog(Project project, String text) {
            super(project, true);
            this.text = text;
            setTitle("导师记住的项目画像");
            setOKButtonText("关闭");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JBTextArea area = new JBTextArea(text);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setBorder(JBUI.Borders.empty(8));
            area.setCaretPosition(0);
            JBScrollPane scroll = new JBScrollPane(area);
            scroll.setPreferredSize(new Dimension(640, 420));
            return scroll;
        }
    }
}
