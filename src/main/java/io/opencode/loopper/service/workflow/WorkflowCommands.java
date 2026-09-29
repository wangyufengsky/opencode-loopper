package io.opencode.loopper.service.workflow;

import io.opencode.loopper.persistence.WorkflowCommandMapper;
import io.opencode.loopper.persistence.WorkflowRows;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.ConflictException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A durable acknowledgement is committed with the change. Replay does not apply it again. */
@Service
public class WorkflowCommands {
    private final WorkflowCommandMapper mapper;
    private final WorkflowEncoding encoding;
    public WorkflowCommands(WorkflowCommandMapper mapper, WorkflowEncoding encoding) { this.mapper = mapper; this.encoding = encoding; }
    public record Receipt(String id, int revision, long version, long layoutVersion, String state) { }
    public Optional<Receipt> replay(String key, String digest) {
        return replay(key, digest, Receipt.class);
    }
    public <T> Optional<T> replay(String key, String digest, Class<T> receiptType) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw new BadRequestException("WORKFLOW_REQUEST_KEY_INVALID", "请使用有效的操作标识");
        var previous = mapper.find(key);
        if (previous.isEmpty()) return Optional.empty();
        if (!previous.get().requestSha256().equals(digest)) throw new ConflictException("WORKFLOW_REQUEST_CONFLICT", "相同操作标识不能用于不同的内容或目标");
        return Optional.of(encoding.decode(previous.get().receiptJson(), receiptType));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt record(String key, String digest, String type, String action, Receipt receipt) {
        return record(key, digest, type, action, receipt.id(), receipt);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T record(String key, String digest, String type, String action, String id, T receipt) {
        if (mapper.insert(new WorkflowRows.Command(key, digest, type, id, action, encoding.encode(receipt), Instant.now().toString())) != 1)
            throw conflict();
        return receipt;
    }
    public static ConflictException conflict() { return new ConflictException("WORKFLOW_VERSION_CONFLICT", "流程或计划已变化，请刷新后重试"); }
}
