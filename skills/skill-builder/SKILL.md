---
name: skill-builder
description: When a plan would benefit from established best practices, research them on the web and capture them as a new, topic-named skill for reuse across the plan lifecycle.
when_to_use: Use while planning or reviewing a plan for a domain where external best practices likely exist and are not already captured as a skill (e.g. "design a REST API", "set up CI", "write a migration"). Skip for trivial tasks or where a relevant skill already exists.
argument-hint: the topic or plan step to research best practices for
allowed_tools: web_search, web_fetch, search_skills, save_skill, load_skill
---
Research and capture best practices for the topic, then make them reusable: $ARGUMENTS

Work in order; do not skip the "is this worth it" check.

1. Decide if a skill is warranted. A new skill is worth building only when BOTH hold:
   - the topic has real, external best practices (conventions, checklists, pitfalls) that go beyond
     common sense, and
   - those practices would change how the plan is prepared, reviewed, or implemented.
   If either is false, stop and say so — do not create a low-value skill.

2. Avoid duplication. Call `search_skills` (and check the available-skills list) for the topic first.
   If a suitable skill already exists, load it with `load_skill` and use it instead of building a new one.

3. Research. Use `web_search` for the topic's best practices, then `web_fetch` the most authoritative,
   primary sources (official docs, standards, well-regarded guides) to read them in full. Prefer primary
   sources over aggregators. Note the sources.

4. Distill. Extract only the durable, reusable guidance: a short checklist, the common pitfalls, and any
   decision criteria. Keep it concrete and faithful to the sources; do not pad or invent. Cite sources by
   name/URL where a claim is non-obvious.

5. Build the skill. Choose a clear, topic-relevant kebab-case name (e.g. `rest-api-design`,
   `db-migration-safety`). Call `save_skill` with that name, a one-line description, and a body that is
   directly usable at each plan-lifecycle stage:
   - Preparing a plan: the checklist of things to include.
   - Reviewing a plan: what to verify is present and correct.
   - Sub-planning a step: how to break the step down per best practice.
   - Selecting a tool for a step: what capabilities the best practice implies a tool should have.
   - Evaluating fit-to-goal: criteria for whether the plan actually serves the stated goal.
   - Post-implementation review: how to judge whether the result met the goal.

6. Use it. Load the new skill with `load_skill` and apply it to the current plan. The skill is now
   auto-indexed and available for future plans on this topic.

Keep the skill focused on ONE topic. If the research spans several distinct topics, prefer several small
skills over one sprawling skill. Be honest in the body about anything uncertain or context-dependent.

Note: skills surface by relevance (load_skill). To have a skill applied automatically at a specific planning
stage, bind it via `skills.lifecycle` (e.g. `prepare=<this-skill>`); stages are prepare, review, sub-plan,
tool-select, goal-eval, post-mortem. See docs/PLAN_LIFECYCLE.md.
