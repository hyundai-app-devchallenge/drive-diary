package com.dongbantest.app.domain.location

import com.dongbantest.app.domain.model.DriveSummary
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.VehicleSignal

/** 위치 목록 + 차량 신호 → 주행 요약(점 개수, 거리, 타임라인). */
fun summarizeDrive(
    points: List<LocationPoint>,
    finished: Boolean,
    signals: List<VehicleSignal> = emptyList(),
): DriveSummary =
    if (points.isEmpty()) {
        DriveSummary.EMPTY
    } else {
        DriveSummary(
            pointCount = points.size,
            distanceMeters = DistanceCalculator.totalMeters(points),
            timeline = TimelineBuilder.build(points, finished, signals),
        )
    }
