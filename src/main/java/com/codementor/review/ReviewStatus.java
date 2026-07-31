package com.codementor.review;

/**
 * 评审状态机 —— ReviewSession 是当前评审的唯一真实状态来源。
 *
 * <p>设计要点（来自最终方案第 7 节）：
 * <ul>
 *   <li>UI 不另存一份同步状态，全部从这个状态机读取/订阅。</li>
 *   <li>评审会经历：IDLE → REQUESTING → (SUCCESS | FAILED | EXPIRED)。</li>
 *   <li>"EXPIRED" 是关键状态：旧响应不能显示到新代码上（修正早期"只靠行号"的硬伤）。</li>
 * </ul>
 */
public enum ReviewStatus {
    /** 空闲：还没有发起评审。 */
    IDLE,
    /** 请求中：模型调用进行中。 */
    REQUESTING,
    /** 成功：拿到结构化建议。 */
    SUCCESS,
    /** 失败：解析失败或网络错误。 */
    FAILED,
    /** 已过期：文档版本/指纹不匹配，旧结果作废。 */
    EXPIRED
}
