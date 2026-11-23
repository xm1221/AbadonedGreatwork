# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 0.0.1

### Added

- Initial version.
- Migrated and upgraded HexGuide's player notes & Amethyst Pen system:
  - Server-side `NoteLibrary` (`SavedData`), `NoteIota`, `ItemAmethystPen`, `ItemNoteScrap` (with paper-per-page transcription and non-consumptive preview/import), and `note/list`, `note/get`, `note/import`, `note/delete` Hex actions.
  - Dynamic Patchouli `thehexbook` entry injection (`BookContentsBuilderMixin` / `BookEntryMixin`) with per-page fault isolation and macro sanitization.
  - Texture-driven open-book GUI skin engine (`gui_skins/*.json` with `"extends"` inheritance + `512x256` `textures/gui/notes/atlas.png`) powering `NoteLibraryScreen`, `NoteEditorScreen` (visual left-page `NotePageCanvas` for 11 page types, native `GuiSpellcasting` pattern drawing pad via `PatternDrawModal`, visual pickers/modals, Patchouli text formatting toolbar & capacity guides, async save/discard guards), and `NoteScrapPreviewScreen`.
  - Client-local JSON export/import (`/abwnotes export | import | open` and library UI buttons).
