# Standards-based bookmark classification

Tidilo can assign a page-kind category and a topic subcategory when saving a bookmark without AI. This applies to manual and shared Direct imports, RSS Direct imports, and saves that skip AI or use the configured AI fallback. Classification runs on the device and reuses the HTML acquired by the import. It requires no additional request, saved history, or user setup.

## Evidence and precedence

1. Read Schema.org JSON-LD from the HTML. Recognize full Schema.org identifiers and compact names under a supported Schema.org context. Resolve references within the document and skip malformed or unsupported metadata.
2. Prefer recognized nodes associated with the current page through `mainEntity` or `mainEntityOfPage`. Resolve fragment references to the corresponding page or content node. If these relationships yield no supported types, accept a recognized top-level or graph node whose `url` matches the page only when the matching node is unique.
3. Evaluate each axis independently. A single distinct Schema.org label sets that axis. Conflicting Schema.org labels leave the affected axis empty. If Schema.org yields no category, use the first `og:type` declaration directly in the HTML head when its value is supported. Open Graph supplies category only.

URLs identify the page and its nodes. Titles, descriptions, headings, entity tags, host names, and path keywords contribute no classification evidence. The identifier mappings are independent of the page's language.

Open Graph type values are matched case-insensitively. For example, `VIDEO.OTHER` and `Video.Other` both match `video.other`.

## Supported mappings

| Source | Identifier | Category | Subcategory |
| --- | --- | --- | --- |
| Schema.org | `Article`, `NewsArticle` | `Article` | — |
| Schema.org | `TechArticle` | `Article` | `Tech` |
| Schema.org | `Recipe` | — | `Food` |
| Schema.org | `Product` | `Commerce` | — |
| Schema.org | `VideoObject` | `Media` | — |
| Schema.org | `DiscussionForumPosting` | `Community` | — |
| Schema.org | `Dataset` | `Reference` | — |
| Schema.org | `LearningResource` | — | `Education` |
| Open Graph | `article` | `Article` | — |
| Open Graph | `video.movie`, `video.episode`, `video.tv_show`, `video.other` | `Media` | — |

A primary `Recipe` paired with `og:type=article` yields `Article / Food`. A recipe without category evidence gets only `Food`. A `Product` with an unrelated embedded video gets `Commerce`.

## Saving and persistence

- Unsupported or missing evidence leaves the corresponding axis null. A bookmark can have both axes, either one, or neither. Entity tags follow the existing manual-import or RSS rules independently.
- Prepared tasks store the two nullable IDs and an evaluation marker so later saves can reuse the result. Room migration 16 to 17 preserves existing queued data and marks older rows as unevaluated. Normal HTML acquisition or refresh supplies new evidence.
- Rule-classified bookmarks retain `isAiSkipped = true`. Successful AI analysis uses its own output. Existing bookmarks are not reclassified in bulk.

See also [Import task and Room models](data_models.md), [RSS imports](rss.md), and [AICore fallback](aicore.md).
