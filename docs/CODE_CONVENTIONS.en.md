[한국어](CODE_CONVENTIONS.md) | **English**

# Code Conventions

## Purpose of this document

This project was built as several parts — API server, relay, worker, search — each implemented independently. Each part uses a different language and framework, but **the reader reads it as one project.** If the way code reads differs from part to part, the whole thing feels scattered.

So this document doesn't contain framework-specific rules — only **standards that any part can follow.** There are three criteria:

| Criterion | What it asks |
| --- | --- |
| Readability | Can a first-time reader immediately tell what this code does? |
| Comment usefulness | Does the comment contain something the code alone can't tell you? Is there a comment that could be removed without loss? |
| Structural soundness | Is it obvious why this code lives here? Are the boundaries clear? |

Each item is written not as "follow this" but in a **checkable form.** When judgment differs, this document is the standard.

---

# 1. Comments

The most important item. More comments isn't better, and fewer isn't better either. A comment should contain **only what the code itself cannot say.**

## Judgment rule

A comment plays one of three roles. If it plays none of them, delete it.

| Role | Where it goes | Length |
| --- | --- | --- |
| Signpost | Each step of a long-flow function | One-line summary |
| Contract | Head of a public function/class | Two to three lines |
| Rationale | Directly above code that embeds a judgment call | One or two sentences |

This doesn't mean "write fewer comments." **Write signposts and contracts proactively.** What gets deleted is a comment that just translates a single line of code.

## Signpost comments

Breaks a multi-line flow into steps. Its role is to show the skeleton before the reader reads the code itself.

- Write it short, as a **summary noun phrase** like "~lookup", "~validation", "~save", "~processing"
- If it just translates the line right below it, it's a candidate for deletion
- Skip it when the flow is short and reads fine from the names alone

```
// Validate upload permission
...three lines...

// Issue and save the version number
...five lines...
```

## Contract comments

On public functions and classes, write **what the caller needs to know.** One line for what it does, followed by any conditions or caveats the caller must observe.

- Don't restate what the name already makes clear
- Instead of listing parameters, state constraints that the name doesn't reveal
- State what this function guarantees and what it doesn't

```
/**
 * Picks up to [limit] rows whose turn it is to be published, marks them as
 * "claimed by me," and returns them.
 *
 * Selecting and marking happen in one statement, so no other instance can
 * slip in between the two.
 */
```

## Rationale comments

Where a judgment call is made, leave the reason behind it. Five cases apply here.

**① Why this choice** — why this approach was picked

```
// Selection and marking are combined into one statement. Splitting them
// would let another instance pick up the same row in between.
```

**② The alternative that was rejected** — why the seemingly easier approach wasn't used

```
// Not wrapped in a transaction. It isn't committed together with the DB
// transaction anyway, so the cost outweighs what it would buy.
```

**③ Why this value** — why a number is what it is

```
// The 10-second margin adds a safety buffer on top of the 5-second connection
// timeout. Setting it to 30 seconds would be arithmetically incompatible with
// the demo configuration.
```

**④ External constraint** — a circumstance we didn't decide

```
// This column's schema is owned by the API server. Here we only check that
// it's correct — we don't fix it.
```

**⑤ Trap** — a spot where reading the code at face value leads to a wrong conclusion

```
// getLong doesn't throw on SQL NULL — it returns 0. wasNull must be checked
// alongside it.
```

## Comments to delete

- **A comment that just restates one line of code as-is.** `// look up by user ID` above `findByUserId(userId)`
- **Commented-out code.** History already lives in git
- **A TODO/FIXME with no owner and no deadline.** If you keep one, state what, why, and by when
- **Auto-generated boilerplate.** A doc comment that only lists `@param` with no explanation
- **Change history.** `// modified 2026-08-12`
- **Decorative separators.** `// ===== lookup starts here =====`

## Comments to fix

- **A comment that has drifted from the code.** The most dangerous case — it sends the reader in the wrong direction. Fix or delete it the moment it's found
- **An overly long comment.** Trim it to the essential sentence or two. If the background needs a long explanation, move it to a document and link it
- **A comment that crams multiple judgment calls into one block.** Split it and place each part next to the relevant code

## Style

With multiple parts, if the writing style diverges, it no longer reads as one project. Standardize on the following.

- **Write in plain declarative form.** End sentences with statements of fact ("does X," "does not do X," "must do X"). Don't use polite/formal endings or casual spoken style
- **Write signpost comments as summary noun phrases.** "~lookup", "~validation", "~save"
- **Don't use speculation or hedging.** Instead of "might do X" or "it might be better to do X," state it as fact. If you're not sure, check first and then write it
- **Leave out emotion and apology.** "Unfortunately," "regrettably"
- Write in Korean. Leave proper nouns and API names as-is in their original form
- Use the language's own doc-comment syntax (`/** */`, `"""docstring"""`) for descriptions of public functions/classes

```
// Good
// If this value is shorter than this, an in-progress batch will be
// terminated before it finishes.

// Bad — hedging, casual spoken style
// This value should probably be set a bit generously, I think.
```

---

# 2. Readability

## Names

- Make the name state the **role**. Don't put the type or data structure in the name (`userList` → `users`, `dataMap` → `versionsByDocument`)
- Don't coin abbreviations. Use only abbreviations already established in the project
- Booleans should state the condition that's true (`deleted`, `hasNext`, `canRetry`)
- Use a verb in function names, and check that the verb matches the actual behavior. If a "lookup" function mutates a value, or `update` creates something new, the name is lying
- **Call the same concept by the same name across the whole project.** If different parts name it differently, the whole thing looks scattered. Use the following terms as the standard

| Concept | Name to use | Names to avoid |
| --- | --- | --- |
| Document identifier | `documentId` | `docId`, `document_no` |
| Document version number | `versionNo` | `version`, `versionNumber` |
| Document version identifier | `documentVersionId` | `versionId` |
| Event identifier | `eventId` | `messageId` |
| Tenant identifier | `tenantId` | `orgId` |
| Trace identifier | `traceId` | `requestId` |

When a new shared concept comes up, add it to this table and align across parts.

## Functions

- A function does one thing. If describing it needs "and," that's a place to split it
- **Keep the abstraction level consistent** within one function. Don't mix a line describing business flow with a line slicing bytes in the same function
- Keep nesting shallow. Handling exceptional cases first and returning early (early return) flattens the body
- When there are many parameters, group them. It's especially easy to get the order wrong at the call site when several parameters share the same type
- Keep public scope to a minimum. Expose only what genuinely needs to be used independently from outside

## Values

- Don't hardcode magic numbers into the code. Pull them out into named constants or configuration
- Pull operational values — timing, size, count — into configuration. Give a default and record the rationale in a comment
- Don't leave hardcoded addresses, paths, or credentials in the code. Read them from environment variables

## Things to remove

- Unused functions, classes, variables, imports
- Unreachable branches
- Code left over from experimentation
- Functions that differ only in name but do the same thing

A reader assumes code exists for a reason. Leaving unused code around breaks that assumption.

---

# 3. Structure

## Boundaries

- **Distinguish what your part owns from what belongs to others.** Don't modify a schema, contract, or data owned by another part from within your own part. Only check whether it's correct, and surface it visibly if it isn't
- When changing a contract between parts (event format, API response, DB schema), align with the other part before shipping
- On code that depends on a contract, leave a comment stating **who owns that contract**

## Placement

- Group files by feature first, then split by role within that
- Once a file starts holding multiple concerns, that's a place to split it
- Put concepts shared across multiple parts in a common location; keep concepts used in only one place there
- Keep the dependency direction one-way. If parts start calling each other, the structure is wrong

## Layers

Names differ from part to part, but the roles are the same.

| Role | Does | Does not do |
| --- | --- | --- |
| I/O | Receives requests/messages, validates format, and passes them on | Business judgment, persistence |
| Business | Determines flow, checks rules, changes state | Handling I/O format |
| Storage | Reads and writes | Business judgment |

- If business judgment ends up in the I/O layer, move it
- If the business layer needs to know the request format, the boundary is drawn wrong
- If the storage layer makes business-rule judgments, move it

## Duplication

- Once the same logic appears a third time, consolidate it. Leave it as-is up to the second occurrence
- Don't force together code that merely happens to look the same. Only consolidate things that would change together

---

# 4. Failure handling

The reader must be able to tell "what happens if this code fails."

- At external boundaries (DB, HTTP, message broker, file), **distinguish an empty value from a default value.** Don't let an absent value masquerade as `0` or an empty string
- **Never fail silently and wrongly.** The worst code is code where a wrong value flows through with no error and no log
- Be wary of a path that ends in "0 results" instead of an error when a condition isn't met. Zero results doesn't look like an error
- Don't catch and swallow exceptions. If you must swallow one, leave a comment explaining why it's acceptable
- **Prevent startup** rather than running with a broken configuration
- Don't put sensitive information or the full request body into logs or error messages

---

# 5. Testing

- A test name states the **scenario and the expected outcome.** Don't use implementation-describing phrases like "calls ~"
- If the name alone isn't enough, leave a comment stating "what's wrong if this assertion breaks"
- After fixing a defect, **confirm the test actually fails when the fix is reverted.** An unverified test doesn't prevent regressions
- A test that changes the configuration of a shared resource should create a dedicated resource so it doesn't affect other tests
- For a test that fails intermittently, run it the same number of times before and after the change and compare, before concluding on a cause

---

# 6. Commits and PRs

- Commit messages use the format `{type}: {summary}` (`feat`, `fix`, `docs`, `chore`, `refactor`)
- Write the summary to reveal **what the problem was**, more than what was fixed
- Split commits and PRs by unrelated cause. Bundle into one commit only changes that can't stand without each other
- Don't mix a cleanup commit (comments, names, dead code) with a commit that changes behavior. Mixing them makes review impossible
- The PR description should state what the problem was, how to reproduce it, what was fixed, and how it was verified

---

# Pre-submission checklist

Each part owner checks this against their own part.

**Comments**

- [ ] Removed comments that just restate the code
- [ ] Removed commented-out code
- [ ] No comments have drifted from the code
- [ ] Numbers (timeouts, limits, sizes) have their rationale written down
- [ ] Code that depends on a cross-part contract states its owner

**Readability**

- [ ] Names state the role
- [ ] Shared concepts are called by the terms in the terminology table
- [ ] Removed unused code and imports
- [ ] Pulled magic numbers out into constants or configuration
- [ ] No hardcoded addresses or credentials

**Structure**

- [ ] Layer roles aren't mixed
- [ ] Didn't modify something owned by another part from within your own
- [ ] No mutual (circular) dependencies

**Failure**

- [ ] No path fails silently and wrongly
- [ ] Swallowed exceptions have a reason written down

**Testing**

- [ ] Test names state the scenario and expected outcome
- [ ] Tests accompanying a defect fix actually catch the regression
