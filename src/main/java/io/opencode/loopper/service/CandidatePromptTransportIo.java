package io.opencode.loopper.service;

import io.opencode.loopper.runtime.OpenCodeClient;

final class CandidatePromptTransportIo implements CandidatePromptDispatchService.PromptIo {
    private final DesignerModelPromptTransport modelPrompts;

    CandidatePromptTransportIo(DesignerModelPromptTransport modelPrompts) {
        this.modelPrompts = modelPrompts;
    }

    @Override
    public OpenCodeClient.MessageLookup lookup(OpenCodeClient.OpenCodeSession remote,
            OpenCodeClient.PromptRequest request, String sha256) {
        return modelPrompts.lookupPrompt(remote,
                new DesignerModelPromptTransport.PreparedPrompt(request, sha256));
    }

    @Override
    public void dispatch(OpenCodeClient.OpenCodeSession remote, OpenCodeClient.PromptRequest request) {
        modelPrompts.dispatchPrompt(remote, new DesignerModelPromptTransport.PreparedPrompt(
                request, OpenCodeClient.promptRequestSha256(request)));
    }
}
