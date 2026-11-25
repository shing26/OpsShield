package com.opspilot.incident;

import java.util.List;

/**
 * 事故列表的一页：{@code openCount} 是本租户进行中总数，不受筛选与游标影响，
 * 顶栏计数必须用它而不是已加载数组长度。
 */
public record IncidentPage(List<Incident> incidents, String nextCursor, boolean hasMore, int openCount) {
}
