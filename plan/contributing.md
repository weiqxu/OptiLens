# Contribution conventions

## Project language

**English is the required language throughout this project.** Use English for documentation and plans, user interface text, error messages, logs, code identifiers and comments, test descriptions and example explanations, commit messages, and pull request descriptions. All new contributions and updates must follow this convention. Assistant replies are an explicit exception: they follow the language of the user’s question; generated code and rule identifiers remain English.

The documentation, interface, built-in examples, rule catalog descriptions, and application-generated messages use English. Historical and imported records retain their original content and language, and user-supplied SQL and data are not translated.


## Code formatting

Java production code, tests and engine adapters use
[Google Java Style](https://google.github.io/styleguide/javaguide.html), applied
by google-java-format 1.24.0 through Spotless Maven 2.43.0. These pinned versions
run on the project's JDK 17 baseline. Google formatting uses two-space block
indentation and a 100-column target; let the formatter choose line breaks.
This enforces formatting, not every semantic rule in the Google style guide.

Frontend JavaScript, HTML, CSS, browser/unit tests, Maven XML, npm configuration
and CI YAML use Prettier 3.6.2 with plugin-xml 3.4.2. JavaScript uses two spaces,
single quotes, semicolons and a 100-column target. This is the project's
Prettier convention, not a claim of exact Google JavaScript Style compliance.
`.editorconfig` sets UTF-8, LF and a final newline for editors.

Install formatting dependencies once:

```bash
npm ci
```

Apply both formatters from the repository root:

```bash
mvn spotless:apply
npm run format
```

Check formatting without changing files:

```bash
mvn spotless:check
npm run format:check
```

The regular Maven `validate` phase checks Java formatting, including adapter
sources, so `mvn test` and `mvn package` reject unformatted Java. The code
formatting GitHub workflow checks both tools on pushes and pull requests.
Node.js is a development-only requirement; running OptiLens still needs only Java.

Formatting excludes generated rule inventories, fixtures, pinned third-party
source archives, Markdown documentation, screenshots, build output and local
run records. Do not run formatters over upstream source archives or generated
evidence files. Python and shell tools retain their existing conventions;
Python uses four spaces as specified in `.editorconfig`.
