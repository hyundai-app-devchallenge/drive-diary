package com.dongbantest.app.domain.location

import com.dongbantest.app.domain.model.DriveSummary
import com.dongbantest.app.domain.model.LocationPoint

/** 위치 목록 → 주행 요약(점 개수, 거리, 타임라인). */
fun summarizeDrive(points: List<LocationPoint>, finished: Boolean): DriveSummary =
    if (points.isEmpty()) {
        DriveSummary.EMPTY
    } else {
        DriveSummary(
            pointCount = points.size,
            distanceMeters = DistanceCalculator.totalMeters(points),
            timeline = TimelineBuilder.build(points, finished),
        )
    }
