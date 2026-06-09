package com.example.demo.mapper;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.demo.DTO.WorkspaceResponse;
import com.example.demo.entity.Workspace;

@Component
public class WorkspaceMapper {

    public WorkspaceResponse toResponse(Workspace workspace) {

        List<String> encoded = workspace.getUpdates()
                .stream()
                .map(Base64.getEncoder()::encodeToString)
                .collect(Collectors.toList());

        return new WorkspaceResponse(
                workspace.getId(),
                encoded
        );
    }
}