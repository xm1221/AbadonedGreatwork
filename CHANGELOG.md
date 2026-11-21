# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 0.0.1

### Added

- Initial version.
- Migrated and upgraded HexGuide's player notes & Amethyst Pen system:
  - Server-side `NoteLibrary` (`SavedData`), `NoteIota`, `ItemAmethystPen`, `ItemNoteScrap` (with paper-per-page transcription and non-consumptive preview/import), and `note/list`, `note/get`, `note/import`, `note/delete` Hex actions.
  - Dynamic Patchouli `thehexbook` entry injection (`BookContentsBuilderMixin` / `BookEntryMixin`) with per-page fault isolation and macro sanitization.
  - Texture-driven GUI skin engine (`gui_skins/*.json` + `textures/gui/notes/atlas.png`) powering `NoteLibraryScreen`, `NoteEditorScreen` (11 Patchouli/Hex/Iota page types + live book preview), and `NoteScrapPreviewScreen`.
  - Client-local JSON export/import (`/abwnotes export | import | open` and library UI buttons).
