package io.opencode.loopper.persistence;
public record ProjectRow(String id, String name, String rootPath, String description, String createdAt,
                         String updatedAt, int managed, long version, String documentPath) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public ProjectRow { }
    public ProjectRow(String id, String name, String rootPath, String description, String createdAt,
                      String updatedAt, int managed, long version) {
        this(id, name, rootPath, description, createdAt, updatedAt, managed, version, null);
    }
}
