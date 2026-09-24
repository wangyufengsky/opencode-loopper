"]};
  JSON_PATH adds jsonPath, expectedValue, and matchMode; FILE_CONTENT uses path, expectedContent,
  matchMode, and criterionIds; FILE_HASH uses path, expectedSha256, and criterionIds;
  DATABASE_QUERY uses path, sql, expectedRowCount, and criterionIds; BROWSER uses url, criterionIds,
  and assertion objects {"type":"EXISTS|VISIBLE|TEXT_CONTAINS|COUNT|ATTRIBUTE_EQUALS","selector":"...","value":"... or null","attribute":"... or null","expectedCount":1}.
  FILE_NOT_EXISTS, JUNIT_XML, and legacy advisory FILE_EXISTS use a path and cannot cover behavior.
- implementationKind is exactly JAVA_PRODUCTION, JAVA_TEST_ONLY, or NON_JAVA. JAVA_PRODUCTION puts
  production Java and its focused Maven/Gradle PROCESS TEST in the same stage, and that TEST's
  criterionIds covers every MACHINE/BOTH criterion in the stage.
- Every stage sets workPackageId to "