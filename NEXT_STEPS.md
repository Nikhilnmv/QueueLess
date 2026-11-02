# Next steps

These notes record what is left, so another session (local or cloud) can continue without the
original conversation. Delete this file and the pointer in `AGENTS.md` once the steps below are
done.

## Where things stand (2 November 2026)

Done on branch `ui-redesign`, which is pushed to GitHub:
- **UI redesign:** every page uses the hand-made components in `frontend/src/components/ui.tsx`.
- **Checks:** `./mvnw verify` (70 unit + 70 integration tests), `npm run build` and
  `npx playwright test` (6 of 6) all passed, and every screen was checked in a browser.
- **Diagrams:** 14 Mermaid diagrams with PNGs in `docs/diagrams`. Refresh them with
  `node scripts/render-diagrams.mjs`.
- **Guides:**
  - `docs/user-guide.md`: running it, demo accounts, roles, demo script
  - `docs/study-guide.md`: the viva guide, replacing the old `viva-preparation.md`
- **Docs tidied:** the README, deployment, testing, roadmap, architecture and ADR 0002 no longer
  mention the removed CI, Docker Compose, Makefile or CDS.

`main` does not have this work yet.

## 1. Merge into main

`main` has no commits of its own since the branch was created, so this is a fast-forward. Render
deploys `main`.

```bash
git switch main && git merge --ff-only ui-redesign && git push origin main
```

## 2. Deploy on Render and Neon

The account steps belong to the owner: `docs/deployment.md`, steps 2 to 5. Afterwards, fill in
the deployment record at the end of that file with the URL and the smoke-test results.

## 3. Project report (on hold)

Not wanted yet. Write it only when the owner asks for it.

When it is wanted, write `docs/report/project-report.md` in the usual university format:
abstract, introduction, existing systems, requirements, design with the diagrams, implementation,
testing (with real numbers only), deployment, results with screenshots, limitations, conclusion
and references.
- Take the content from `docs/study-guide.md` and the other docs. Do not invent features or
  results.
- Headings-only drafts also exist as Claude Docs in the owner's account.

## 4. Finish

Delete this file and the pointer at the top of `AGENTS.md`.
