package com.codementor.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 教练侧栏工厂 —— 由 plugin.xml 的 toolWindow 扩展点注册创建。
 *
 * <p>实现 {@link DumbAware}：即使在"建立索引中"（Dumb Mode）也能打开侧栏，
 * 阶段 0 不依赖索引就绪就能用。
 */
public class MentorToolWindowFactory implements ToolWindowFactory, DumbAware {

    /** Tool Window 的 ID，必须与 plugin.xml 里的 id 一致。 */
    public static final String TOOL_WINDOW_ID = "CodeMentor";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MentorToolWindowPanel panel = new MentorToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
