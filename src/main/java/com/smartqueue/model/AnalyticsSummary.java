package com.smartqueue.model;

import java.util.List;

public record AnalyticsSummary(
        List<Metric> averageWaitMinutes,
        List<Metric> dailyThroughput,
        List<Metric> hourlyDistribution,
        List<Metric> serviceVolume
) {
}