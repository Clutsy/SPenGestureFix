# Translation and Documentation Guide

This project uses English as the source language and ships Italian and Spanish Android resources. The goal is concise, commercial product language rather than literal word-for-word translation.

## Source of truth

- Android source strings: `app/src/main/res/values/strings.xml`.
- Italian resources: `app/src/main/res/values-it/strings.xml`.
- Spanish resources: `app/src/main/res/values-es/strings.xml`.
- Locale declaration: `app/src/main/res/xml/locales_config.xml`.
- Commercial project documentation: root `README.md`.

Every localized catalog must contain the same string names as the English catalog. Never rename a key only in one language. Placeholders such as `%1$s`, `%2$d`, and `%s` must remain unchanged and in a grammatically valid position.

## Product glossary

| English term | Usage guidance |
|---|---|
| S Pen | Keep the product name unchanged in Italian and Spanish. |
| Wacom digitizer | Use for the kernel input component, not “tablet screen”. |
| Extracted / inserted | Use for the physical pen state; avoid ambiguous “connected”. |
| Hover | Use for in-range, non-contact pen movement. |
| Side button | Use for the programmable S Pen button. |
| Wheel / Air Command | “S Pen Wheel” is the product feature name; do not translate Air Command. |
| Tablet Mode | Keep as a feature name when referring to the PC workflow. |
| Overlay permission | Use the platform concept, not “floating permission”. |
| Root | Keep `root` in code and UI; explain it where necessary. |

## Tone

- Prefer short, direct labels: “Start”, “Stop”, “Save”, “Choose an action”.
- Describe outcomes, not implementation details, in the dashboard.
- Use sentence case for buttons and headings.
- Keep technical explanations precise and neutral.
- Do not claim Samsung SDK support on the Note 3 AOSP backend.

## Documentation map

When the architecture changes, update these README sections together:

1. Product capabilities.
2. Hardware architecture and the input conflict explanation.
3. Build/install instructions.
4. Windows ADB emulator usage.
5. Diagnostics and known limitations.
6. Project map and testing commands.

Future documents should use the same structure: purpose, requirements, workflow, troubleshooting, and limitations. Technical comments in Kotlin and Python should remain in English so the codebase remains maintainable across teams.

## Translation workflow

1. Add or update the English key first.
2. Copy the key to Italian and Spanish, preserving placeholders and XML escaping.
3. Translate the meaning in context; check button length on a small phone display.
4. Add the locale to `locales_config.xml` when introducing a new language.
5. Run resource compilation and the i18n parity check before review.
6. Update the README glossary if a new hardware or product term is introduced.

The repository intentionally keeps machine-generated or protocol terms (`getevent`, `sec_e-pen`, `w1`, `BTN_STYLUS`, and file paths) unchanged.
