# Glossary

This document defines the canonical domain terms used across the Tidilo codebase, specifications, and architectural documentation.

## Bookmark Detail Screen Vocabulary

- **Seamless Hero Banner**: The top visual region of a bookmark's detail view where preview artwork (`og:image`) spans edge-to-edge horizontally and dissolves seamlessly into dynamic top and bottom edge-sampled gradients without a visible border rim. When preview artwork is unavailable, it falls back to a watermarked ambient header.
- **Floating Header Actions**: The pair of persistent circular top action buttons—**Back** on the top-left and **Save** (floppy-disk icon) on the top-right—that float above the detail screen with backdrop blur and remain accessible across all scroll positions without a full-width title bar.
- **Source Attribution Row**: The primary metadata line immediately above the bookmark title displaying the publisher's resolved human-readable domain name and relative creation timestamp (`Domain • 3 hours ago`), without a favicon or external browser-jump icon.
- **Classification Breadcrumb**: The optional plain-text line directly beneath the Source Attribution Row and above the bookmark title that formats the bookmark's hierarchical classification and content-safety state (`Category > Subcategory`) in the same subdued caption typography as the Source Attribution Row, collapsing with zero vertical gap when no classification flag is set.
- **Editorial Summary Callout**: The read-only summary container styled with a start-edge vertical accent bar and subtle horizontal tonal wash, visually distinguishing curated or extracted article summaries from the user's editable personal Note field.

## Background and Theming Vocabulary

- **Ambient Lighting Layer**: The root-level, stationary background layer that renders the active theme's canvas color and dynamic gradient beams/glows reflecting ambient hues across screen destinations without sliding during navigation transitions.

