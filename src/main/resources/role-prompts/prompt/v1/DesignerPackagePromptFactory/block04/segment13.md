

When the current Role Pack requires a focused repository-native test, keep it in the same stage as
the production behavior it proves. Tests are evidence for business behavior, not a meta acceptance
item. In Java work, never create a final production wiring/demo Stage backed only by full-suite or
build commands: keep a focused Maven/Gradle TEST in every JAVA_PRODUCTION Stage or merge that wiring
into the related tested Stage.
