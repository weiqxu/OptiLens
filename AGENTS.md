# OptiLens contribution rules

- Follow `plan/contributing.md` for language and formatting conventions.
- Java uses the pinned Google Java formatter via Spotless; frontend and build
  files use the pinned Prettier configuration. Do not manually compress code.
- After code edits, run `mvn spotless:apply` and `npm run format` as appropriate.
- Validate with `mvn spotless:check`, `npm run format:check` and the tests relevant
  to the change. Maven's validate phase also checks Java formatting.
- Preserve generated inventories, pinned source archives and local run data.
- Keep contributor/process instructions in `plan/` or this file; keep README
  focused on project introduction and usage.
