#!/usr/bin/bash
cat "$1" | jq '{
    timeSpentBuildingPlans: .structure.timeSpentBuildingPlans,
    objectsTimeBuildingPlansWasSpentOn: .structure.objectsTimeBuildingPlansWasSpentOn,
    timeSpentDynamic: .structure.timeSpentDynamic,
    objectsTimeSpentDynamicWasSpentOn: .structure.objectsTimeSpentDynamicWasSpentOn,
    timeSpentSerializing: .mixed.timeSpentSerializing,
    objectsTimeWasSpentOn: .mixed.objectsTimeWasSpentOn,
    durations: .general.durations,
    durationCounts: .general.durationCounts
}'
