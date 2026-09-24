package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;

/** Builds interactive Designer and legacy single-draft Compiler prompts from prepared facts. */
final class DesignerConversationPromptFactory {
    String designer(String roleInstructions, String projectRoot, String sessionId,
                    String draftId, String message) {
        return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block01.segment0")
                + String.format("%s", (Object) (roleInstructions))
                + "\n\nRegistered project root: "
                + String.format("%s", (Object) (projectRoot))
                + "\nDesigner session id: "
                + String.format("%s", (Object) (sessionId))
                + "\nBound draft id: "
                + String.format("%s", (Object) (draftId))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block01.segment4")
                + String.format("%s", (Object) (message))
                + "\n");
    }

    String requirementDiscussion(boolean directSoftware, String roleInstructions, String projectRoot,
                                 String sessionId, String previousSnapshot, String feedback,
                                 boolean questionRepair, boolean questionRequired,
                                 boolean nativeQuestion) {
        if (!questionRequired) {
            return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block02.segment0")
                + String.format("%s", (Object) (roleInstructions))
                + "\n\nProject root: "
                + String.format("%s", (Object) (projectRoot))
                + "\nDesigner session: "
                + String.format("%s", (Object) (sessionId))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block02.segment3")
                + String.format("%s", (Object) (previousSnapshot))
                + "\n\nUser's direct chat answer:\n"
                + String.format("%s", (Object) (feedback))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block02.segment5"));
        }
        if (!nativeQuestion) {
            return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block03.segment0")
                + String.format("%s", (Object) (roleInstructions))
                + "\n\nProject root: "
                + String.format("%s", (Object) (projectRoot))
                + "\nDesigner session: "
                + String.format("%s", (Object) (sessionId))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block03.segment3")
                + String.format("%s", (Object) (previousSnapshot))
                + "\n\nNew user input:\n"
                + String.format("%s", (Object) (feedback))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block03.segment5"));
        }
        if (directSoftware) {
            return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block04.segment0")
                + String.format("%s", (Object) (roleInstructions))
                + "\n\nProject root: "
                + String.format("%s", (Object) (projectRoot))
                + "\nDesigner session: "
                + String.format("%s", (Object) (sessionId))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block04.segment3")
                + String.format("%s", (Object) (previousSnapshot))
                + "\n\nNew user input:\n"
                + String.format("%s", (Object) (feedback))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block04.segment5")
                + String.format("%s", (Object) (questionRepair ? RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.question-repair-guidance") : ""))
                + "\n");
        }
        return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block05.segment0")
                + String.format("%s", (Object) (roleInstructions))
                + "\n\nProject root: "
                + String.format("%s", (Object) (projectRoot))
                + "\nDesigner session: "
                + String.format("%s", (Object) (sessionId))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block02.segment3")
                + String.format("%s", (Object) (previousSnapshot))
                + "\n\nNew user input:\n"
                + String.format("%s", (Object) (feedback))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block05.segment5")
                + String.format("%s", (Object) (questionRepair ? RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.question-repair-guidance") : ""))
                + "\n");
    }

    String compiler(String projectRoot, String projectId, String draftSpec,
                    int designRevision, String design) {
        return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block06.segment0")
                + String.format("%s", (Object) (projectRoot))
                + "\nRequired projectId: "
                + String.format("%s", (Object) (projectId))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block06.segment2")
                + String.format("%s", (Object) (draftSpec))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block06.segment3")
                + String.format("%s", (Object) (projectId))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block06.segment4")
                + String.format("%d", (Object) (designRevision))
                + ":\n"
                + String.format("%s", (Object) (design))
                + "\n");
    }

    String compilerRepair(int repairCount, int maxRepairs, String code, String detail) {
        return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block07.segment0")
                + String.format("%d", (Object) (repairCount))
                + "/"
                + String.format("%d", (Object) (maxRepairs))
                + "\nError code: "
                + String.format("%s", (Object) (code))
                + "\nError detail: "
                + String.format("%s", (Object) (safeMessage(detail)))
                + RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block07.segment4"));
    }

    String redesign(String gaps) {
        return (RolePromptResources.read("prompt.v1.DesignerConversationPromptFactory.block08.segment0")
                + String.format("%s", (Object) (gaps))
                + "\n");
    }

    private String safeMessage(String message) {
        return message == null ? "Unknown error" : message.substring(0, Math.min(message.length(), 4_000));
    }
}
