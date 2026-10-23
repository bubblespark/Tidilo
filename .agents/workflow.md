# Agent Workflow & Development Rules

> **Agent Note**: Read this file at the start of every task. It defines the mandatory 5-step workflow, GitHub rules, versioning policy, and commit conventions that all agents must strictly follow.

This document defines the mandatory, step-by-step workflow and standards that every AI coding agent must strictly adhere to when developing, refactoring, or maintaining the Tidilo repository.

---

## Workflow Overview

```
[Step 1: Analyze] ➔ [Step 2: Plan] ➔ [Step 3: Implement] ➔ [Step 4: Verify] ➔ [Step 5: Document]
```

Every user request must follow these 5 steps in sequence.

---

## Language Standards & Communication Rules

1. **User Conversation Language**:
   - **All conversational interactions, explanations, plan proposals, questions, and handover reports with the user MUST be conducted in Japanese.**
2. **Codebase & Git Contents Language**:
   - All source code comments, KDoc docstrings, Git contents (commit, pull request), and markdown documentation files within the repository MUST be written in English.
3. **UI Localization**:
   - We will generally implement the feature in English.
   - If a user provides instructions in another language, we will translate them into English and implement the feature accordingly.
   - Currently, this application supports only English and Japanese. With a view to supporting multiple languages in the future, it should be designed to allow for localization.
   - Any text revisions resulting from the addition or modification of features must be made only in English, the default language.

---

## GitHub Rules

1. **Use the GitHub MCP Server**
2. **Retrieve the contents of the issue using the specified issue number**:
   - If you have any questions about the issue contents, ask the user. It is acceptable to pause work.
3. **Create a branch from the main branch:**
   - If the label is "bug" create a branch named `fix/*****`.
   - If the label is "modification" create a branch named `mod/*****`.
   - If the label is "enhancement" create a branch named `feat/*****`.
   - Keep the branch name concise and descriptive.
4. **After completing the work, commit to the branch you created:**
   - Write an appropriate commit message.
   - There is no need to include information about local settings. (For example, "The debug build is already installed," and so on.)
   - Once you have received approval from the user, please run the push command.
5. **Create a pull request:**
   - Describe the changes you made in response to the issue.
   - Include this description in the "Problem/Changes" section.

---

## Application Version Control Rules

User clarification: increment the application version at most once per PR. Review revisions within that PR retain its allocated version.

1. **Source Code Modifications:**
   - Change the version text in the application settings according to the following rules.
2. **Format:**
   - `v[Major].[Minor].[Patch]`
3. **Major:**
   - Do not make any changes unless explicitly instructed to do so by the user.
   - If a change is made, the [Minor] and [Patch] count should also be set to 0.
4. **Minor:**
   - Do not make any changes unless explicitly instructed to do so by the user.
   - If a change is made, the [Patch] count should also be set to 0.
5. **Patch:**
   - Regardless of the "GitHub Rules" label, please increment the count by 1.

---

## Step 1: Analyze & Requirements Discovery

1. **Understand Intent & Constraints**:
   - Accurately grasp the user's intent, Acceptance Criteria / Definition of Done (DoD), and any technical constraints (e.g. offline-first privacy, Material 3 Expressive, edge-to-edge rendering, Android 15/Pixel compatibility).
2. **Inspect Existing Context**:
   - Read relevant existing code, directory hierarchies (`.agents/structure.md`), data models (`.agents/data_models.md`), and dependencies before modifying anything.
   - Check the active task log in `.agent/tasks.md` to understand current sprint status.
3. **Resolve Ambiguities Proactively**:
   - If specifications are unclear or multiple architectural approaches exist with trade-offs, do not make assumptions—clarify with the user before proceeding.
4. **Binary & Asset Constraints**:
   - Do not open or inspect binary image files directly with text tools. Analyze source code, UI mock descriptions, logs, or vector paths instead.

---

## Step 2: Plan & Proposal

1. **Scope-Based Planning**:
   - **Routine / Unambiguous Tasks**: Formulate a concise internal plan covering:
     - Target files to modify or create.
     - Step-by-step checklist.
     - Potential side-effects and risk analysis.
     - *Proceed directly to implementation to maintain development velocity.*
   - **Large / Architectural / Breaking Changes**:
     - Present the structured plan to the user in chat.
     - Highlight design choices and risks.
     - Await user approval or feedback before proceeding to code changes.

---

## Step 3: Implement (Incremental & Standardized)

1. **Compliance with Repository Standards**:
   - Strictly adhere to the specifications in `.agents/` (`coding_conventions.md`, `ui_design.md`, `data_models.md`).
   - Use Jetpack Compose Material 3 Expressive guidelines (rounded pill shapes 20–28dp, tonal elevation, fluid animations).
2. **YAGNI & Single Responsibility**:
   - Implement only what is required to satisfy the user request.
   - Avoid unsolicited refactoring, speculative abstractions, or out-of-scope edits.
3. **Precision & Safety**:
   - Use surgical edits to preserve unrelated comments, docstrings, and imports.
4. **Code Quality & Language Rules**:
   - Maintain strict typing, null-safety, and lifecycle-aware coroutine collection (`collectAsStateWithLifecycle()`).
   - Heavy I/O and parsing must run on `Dispatchers.IO`.
   - **All code comments and docstrings MUST be written in English.**

---

## Step 4: Verify (Automated Testing & Deployment)

1. **Automated Unit Testing**:
   - Run `./gradlew testDebugUnitTest` to verify compilation, type safety, and 100% test pass rate across existing and new tests.
2. **Test Coverage Expansion**:
   - When introducing new business logic, domain models, or utility functions, add corresponding unit tests in `app/src/test/` to prevent regressions.
3. **Debug Installation on a Virtual Device**:
   - Use `adb devices` to list the devices. If there are multiple devices, install the debug build on the first one. Since users will perform testing using the emulator, simply installing the app is sufficient.
4. **Testing in an Emulator Using AICore**:
   - AICore features are not available in the emulator. Therefore, you do not need to test new features or changes that rely on these features.

---

## Step 5: Document Synchronization

1. **Architecture & Guidelines Synchronization (`.agents/`)**:
   - If UI patterns, data models, or system architecture were added or altered, update the corresponding markdown file under `.agents/` (e.g. `ui_design.md`, `data_models.md`).
2. **Master Task Tracker Update (`.agents/tasks.md`)**:
   - Prepend the completed task summary to the top of the `Completed Milestones` section in `.agents/tasks.md`.

---

## Step 6 (Optional): Revisions Based on Reviewers' Comments

> **Execute only when instructed by the user.**

1. **Retrieve all comments from the pull request you created**:
   - The reviewer's name is "coderabbitai." Extract the comments with that name.
2. **Review the reviewer's comments**:
   - Please explain whether the point raised is valid or, if it is unnecessary, why.
3. **Obtain approval from the user**:
   - We will correct the issues that have been approved.

   ### A commit with relatively large changes
   - Commit each change.
   - Examples include modifications to processing logic, changes to specifications, refactoring, and changes to dependencies or configuration files, among others.

   ### A commit containing minor changes
   - Combine the commits into one.
   - Examples include correcting typos, standardizing inconsistent terminology, adjusting formatting, and making minor corrections for the same reasons.

## Agent Planning Documents

- Store agent implementation plans, brainstorming notes, and Superpowers workflow artifacts under `.agents/superpowers/`, never under `docs/superpowers/`.
- Reserve `docs/` for product specifications and developer-facing documentation.
- When a task explicitly requests committing these artifacts, stage only the requested `.agents` paths with `git add -f`; the directory is otherwise ignored. Never stage unrelated local agent files.
