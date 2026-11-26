# Diff walkthrough

A local viewer for reading a git diff with an annotated walkthrough. The notes stay on the left and the full diff stays on the right. Each column scrolls on its own. Clicking a section, or a citation in the notes, jumps to that file and line range in the diff and highlights it. A numbered marker in the diff jumps back to the section that explains it. Each section has a reviewed checkbox, stored in `localStorage`.

The tool is standalone. It does not use the rest of this repository or its build.

## Requirements

- Python 3
- Git
- A browser
- Network access the first time a page loads [diff2html](https://github.com/rtfpessoa/diff2html) from jsDelivr

## Usage

From the repository root:

```bash
./diff-walkthrough/diff-walkthrough \
  --range main...HEAD \
  --walkthrough notes.md
```

The command writes `diff-walkthrough.html` in the current directory and opens it. Other flags:

| Flag | Meaning |
| --- | --- |
| `--repo PATH` | Working tree to diff. Default: the current directory. |
| `--side-by-side` | Two-column diff. The default is a single unified column. |
| `--no-open` | Write the HTML file and do not open a browser. |
| `--out FILE` | Output path. Default: `diff-walkthrough.html`. |

The range is passed to `git diff -U3` (three lines of context, so the result does not depend on your `diff.context` setting). Use `base...head`, `base..head`, or `COMMIT^..COMMIT` for a single commit. `git diff COMMIT` compares the working tree to that commit, which is usually not what you want.

Citations that are not in the diff are printed as `warning:` lines and shown in yellow on the page. The HTML file is still written.

## Review progress

When you regenerate a walkthrough with the same range and section headings, a section stays checked if its citations still point to the same hunk content. A change to an added, removed, or context line anywhere in a cited hunk reopens that section. A whole-file citation tracks the file's entire patch. The first time you regenerate with this version, sections checked by older viewers start unchecked.

Uncheck a section to review it again, or click **Clear review marks** beside the reviewed count to reset all sections in the current walkthrough.

## Walkthrough format

The notes are Markdown. Start with a `#` title. Each `##` heading is a section with its own reviewed checkbox. Cite a place with an inline code span of the new-side line range:

```markdown
## Parser change
`src/Parser.java:120-140`

The new parser keeps continuation lines.
```

`src/Parser.java:120` is a single line. A code span that is exactly a path from the diff, with no line numbers, jumps to that file. Line numbers are the line numbers in the file *after* the change (the right-hand side), and the end line is inclusive. Only lines that appear in the diff can be highlighted: added lines, and unchanged lines within three lines of a change.

`WALKTHROUGH_PROMPT.md` is the note to give an AI together with a diff. It specifies the format and asks for the launch command.

## Example

This walks through commit `91b56d74` (`fix: mac dock icons`) in the default unified view:

```bash
./diff-walkthrough/diff-walkthrough \
  --range 91b56d743e9ef9462bdae37fc6fc31dce9f2e232^..91b56d743e9ef9462bdae37fc6fc31dce9f2e232 \
  --walkthrough diff-walkthrough/examples/mac-dock-icons.md
```

Add `--side-by-side` to show each file in two columns.

Add `#balloon-utility` to the page URL, or open it with `?section=balloon-utility`, to land on that section. Section ids are the heading text in lowercase with spaces turned into hyphens.
