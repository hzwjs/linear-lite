package com.linearlite.server.controller;

import com.linearlite.server.dto.DocumentCollaborationDtos.AuthorizeRequest;
import com.linearlite.server.dto.DocumentCollaborationDtos.Authorization;
import com.linearlite.server.dto.DocumentCollaborationDtos.EpochRestored;
import com.linearlite.server.dto.DocumentCollaborationDtos.RestoreEpochRequest;
import com.linearlite.server.dto.DocumentCollaborationDtos.StateCommitted;
import com.linearlite.server.dto.DocumentCollaborationDtos.StateResponse;
import com.linearlite.server.dto.DocumentCollaborationDtos.UpdateStateRequest;
import com.linearlite.server.dto.DocumentCollaborationDtos.ValidateUpdateRequest;
import com.linearlite.server.dto.DocumentCollaborationDtos.TransitionState;
import com.linearlite.server.service.CollaborationInternalKeyGuard;
import com.linearlite.server.service.DocumentCollaborationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/collaboration")
public class CollaborationInternalController {
    private final DocumentCollaborationService collaborationService;
    private final CollaborationInternalKeyGuard keyGuard;

    public CollaborationInternalController(DocumentCollaborationService collaborationService,
                                           CollaborationInternalKeyGuard keyGuard) {
        this.collaborationService = collaborationService;
        this.keyGuard = keyGuard;
    }

    @PostMapping("/authorize")
    public Authorization authorize(@RequestHeader(CollaborationInternalKeyGuard.HEADER) String key,
                                   @RequestBody AuthorizeRequest body) {
        keyGuard.requireValid(key);
        return collaborationService.authorize(body);
    }

    @PostMapping("/validate-update")
    public ResponseEntity<Void> validateUpdate(@RequestHeader(CollaborationInternalKeyGuard.HEADER) String key,
                                               @RequestBody ValidateUpdateRequest body) {
        keyGuard.requireValid(key);
        collaborationService.validateUpdate(body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents/{documentId}/state")
    public StateResponse state(@RequestHeader(CollaborationInternalKeyGuard.HEADER) String key,
                               @PathVariable Long documentId, @RequestParam String epoch,
                               @RequestParam(required = false) String transitionId) {
        keyGuard.requireValid(key);
        return collaborationService.readState(documentId, epoch, transitionId);
    }

    @GetMapping("/transitions/{transitionId}")
    public TransitionState transitionState(@RequestHeader(CollaborationInternalKeyGuard.HEADER) String key,
                                           @PathVariable String transitionId) {
        keyGuard.requireValid(key);
        return collaborationService.transitionState(transitionId);
    }

    @PutMapping("/documents/{documentId}/state")
    public StateCommitted storeState(@RequestHeader(CollaborationInternalKeyGuard.HEADER) String key,
                                     @PathVariable Long documentId, @RequestBody UpdateStateRequest body) {
        keyGuard.requireValid(key);
        return collaborationService.commitState(documentId, body);
    }

    @PostMapping("/documents/{documentId}/restore-epoch")
    public EpochRestored restoreEpoch(@RequestHeader(CollaborationInternalKeyGuard.HEADER) String key,
                                      @PathVariable Long documentId, @RequestBody RestoreEpochRequest body) {
        keyGuard.requireValid(key);
        return collaborationService.restoreEpoch(documentId, body);
    }
}
