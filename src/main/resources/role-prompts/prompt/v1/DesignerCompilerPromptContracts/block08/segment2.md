","description":"observable business result","verificationMode":"MACHINE|JUDGE|BOTH","judgeRubric":"required for JUDGE/BOTH or null","judgeOnlyReason":"required only for JUDGE or null"}.
- verificationRuntime is null for PROCESS-only stages and never a test framework name. Only an
  HTTP_STATUS, JSON_PATH, or BROWSER stage that starts its own service uses
  {"startCommand":["java","-jar","app.jar","--server.port={{LOOPPER_PORT}}"],"readiness":{"path":"/actuator/health","expectedStatus":200,"jsonPath":"$.status","expectedValue":"UP","matchMode":"EXACT"},"startupTimeoutSeconds":60,"shutdownTimeoutSeconds":10}.
- Other supported verifier object shapes are:
  GIT_DIFF {"type":"GIT_DIFF","requireChanges":true,"allowedPaths":["src/**"],"forbiddenPaths":[".env"],"forbidDeletes":true};
  HTTP_STATUS {"type":"HTTP_STATUS","url":"http://127.0.0.1:{{LOOPPER_PORT}}/path","httpMethod":"GET","expectedStatus":200,"criterionIds":["