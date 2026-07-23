package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectEmailPreference;
import com.linearlite.server.mapper.ProjectEmailPreferenceMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectEmailPreferenceService {

    public static final String SCENARIO_DAILY_SUMMARY = "daily_summary";

    private final ProjectEmailPreferenceMapper mapper;

    public ProjectEmailPreferenceService(ProjectEmailPreferenceMapper mapper) {
        this.mapper = mapper;
    }

    public void initializeForProject(Long projectId) {
        ProjectEmailPreference preference = new ProjectEmailPreference();
        preference.setProjectId(projectId);
        preference.setScenarioKey(SCENARIO_DAILY_SUMMARY);
        preference.setEnabled(false);
        preference.setCreatedAt(LocalDateTime.now());
        preference.setUpdatedAt(LocalDateTime.now());
        mapper.insert(preference);
    }

    public boolean isEnabled(Long projectId, String scenarioKey) {
        ProjectEmailPreference preference = select(projectId, scenarioKey);
        return preference != null && Boolean.TRUE.equals(preference.getEnabled());
    }

    public void setEnabled(Long projectId, String scenarioKey, boolean enabled) {
        ProjectEmailPreference preference = select(projectId, scenarioKey);
        if (preference == null) {
            preference = new ProjectEmailPreference();
            preference.setProjectId(projectId);
            preference.setScenarioKey(scenarioKey);
            preference.setEnabled(enabled);
            preference.setCreatedAt(LocalDateTime.now());
            preference.setUpdatedAt(LocalDateTime.now());
            mapper.insert(preference);
            return;
        }
        preference.setEnabled(enabled);
        preference.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(preference);
    }

    public List<Long> listEnabledProjectIds(String scenarioKey) {
        return mapper.selectList(
                new LambdaQueryWrapper<ProjectEmailPreference>()
                        .eq(ProjectEmailPreference::getScenarioKey, scenarioKey)
                        .eq(ProjectEmailPreference::getEnabled, true)
        ).stream().map(ProjectEmailPreference::getProjectId).toList();
    }

    private ProjectEmailPreference select(Long projectId, String scenarioKey) {
        return mapper.selectOne(
                new LambdaQueryWrapper<ProjectEmailPreference>()
                        .eq(ProjectEmailPreference::getProjectId, projectId)
                        .eq(ProjectEmailPreference::getScenarioKey, scenarioKey));
    }
}
