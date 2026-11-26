# Annotate this diff for the walkthrough viewer

Write a markdown walkthrough of the diff the user gave you, then give the exact command that opens it in the viewer.

The viewer shows your markdown on the left and the full `git diff -U3` on the right. Clicking a citation scrolls the diff to that file and line range. You choose the citations. The viewer resolves them. It does not use generated HTML ids.

## What to output

Two parts, in this order:

1. The walkthrough markdown, and nothing else in that part. If you can write files, save this part as the walkthrough file. Do not put the launch command inside that file.
2. One shell command that launches the viewer with the range the user named.

If the user did not name a range, do not invent one. Say which range the command still needs. For a single commit the range is `COMMIT^..COMMIT`. A branch range looks like `main...HEAD`.

Typical command, from the repository that contains the tool:

```bash
./diff-walkthrough/diff-walkthrough --range RANGE --walkthrough WALKTHROUGH.md
```

Use `--repo PATH` when the git working tree is not the current directory. The diff is a single unified column. Do not pass `--side-by-side` unless the user asked for a two-column diff.

## Document shape

```markdown
# Short title

One or two paragraphs on what changed and why. No line citations are required here.

## Section title

`path/to/File.java:120-140`

Explain this slice of the change. A section may cite more than one place. Put each citation in its own inline code span.

## Next section

`path/to/Other.java:40-52`
```

- The first `#` heading is the title. It is not a reviewed section.
- Every `##` heading is a section. The viewer adds the reviewed checkbox. Do not write `- [ ]`.
- Use `###` if you need a subheading inside a section. Do not use a second `#`.
- Keep each section to one idea. Prefer several short sections over one long one.
- Markdown such as lists, `inline code`, fenced code, and links is fine.
- Do not put a citation inside a fenced code block. Fenced code is not linked.
- Do not wrap the citation in extra words inside the backticks. The span is the whole reference.

## How to write a citation

A citation is an inline code span in one of these forms:

- `` `path/to/File.java:120-140` `` — inclusive new-side line range
- `` `path/to/File.java:120` `` — one new-side line
- `` `path/to/File.java` `` — the whole file, only when that exact path appears in the diff

Rules:

- `path` is the repository path from the diff, with forward slashes, and without the `a/` or `b/` prefix. For a rename, cite the new path.
- Line numbers are 1-based line numbers in the file **after** the change. They are the new-side numbers, not the old-side numbers and not a count of lines in the hunk.
- The end line is inclusive.
- There is no space around the colon. `File.java:120-140` is valid. `File.java: 120-140` is not a citation.
- Paths may contain spaces. The whole span is still the citation: `` `dir/my file.txt:10-12` ``.
- Do not cite a diff2html id, a hunk header, or a `@@` line.

Count new-side numbers from the hunk header. `@@ -10,3 +10,4 @@` means the next new-side line is 10. Then:

- a line that starts with `+` is new-side line N, then N increases by 1
- a line that starts with a space is new-side line N, then N increases by 1
- a line that starts with `-` is old-side only, and N stays the same
- `\ No newline at end of file` does not change N

```
@@ -10,3 +10,4 @@ class A {
 same          <- new line 10
-old
+new1          <- new line 11
+new2          <- new line 12
 same2         <- new line 13
```

The citation for the two added lines is `` `src/A.java:11-12` ``.

The viewer runs `git diff -U3`, so the diff contains three lines of unchanged context around each change. Cite added lines freely. Cite an unchanged line only when it sits within those three lines. A line that exists in the new file but is not in a hunk cannot be highlighted, and the viewer will show a warning instead of guessing.

Deleted files have no new-side lines. Describe the deletion in prose, or cite the bare path so the view jumps to the file, and do not cite `path:line`. Binary files have no line numbers. For a rename, cite the new path.

## Tone

Describe what the code does after the change, and why the diff is shaped that way. Quote identifiers from the diff. Do not restate every changed line. Skip files that do not help the reader.
