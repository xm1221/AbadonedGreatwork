# Texture-Driven GUI Skins (`gui_skins/`)

All note GUI screens (`NoteLibraryScreen`, `NoteEditorScreen`, `NoteScrapPreviewScreen`) load their texture atlas, nine-slice margins, button states, region coordinates, and text colors from:

- `assets/abadoned_greatwork/gui_skins/note_library.json`
- `assets/abadoned_greatwork/gui_skins/note_editor.json`
- `assets/abadoned_greatwork/gui_skins/note_preview.json`

Changes take effect immediately on client resource reload (`F3+T`). If a sprite key or texture is missing, the renderer draws a magenta/black checkerboard and logs a single warning per missing sprite.

## Default Atlas Layout (`textures/gui/notes/atlas.png`, 256×256)

| Sprite Key | UV `[u, v, w, h]` | Nine-Slice `[l, t, r, b]` | Purpose |
|---|---:|---:|---|
| `panel` | `[0, 0, 48, 48]` | `[8, 8, 8, 8]` | Outer window frame |
| `sub_panel` | `[48, 0, 32, 32]` | `[4, 4, 4, 4]` | Recessed inner panels |
| `page_paper` | `[80, 0, 140, 172]` | `[10, 10, 10, 10]` | Parchment page background |
| `text_field` | `[0, 48, 48, 24]` | `[3, 3, 3, 3]` | Input field frame |
| `list_item_normal` | `[48, 48, 24, 24]` | `[3, 3, 3, 3]` | Unselected list row |
| `list_item_selected` | `[48, 72, 24, 24]` | `[3, 3, 3, 3]` | Selected / hovered list row |
| `button` | `normal [0,80,48,16]`, `hover [0,96,48,16]`, `disabled [0,112,48,16]` | `[4, 4, 4, 4]` | Standard button |
| `button_accent` | `normal [0,128,48,16]`, `hover [0,144,48,16]`, `disabled [0,160,48,16]` | `[4, 4, 4, 4]` | Primary action button |
| `button_danger` | `normal [0,176,48,16]`, `hover [0,192,48,16]`, `disabled [0,208,48,16]` | `[4, 4, 4, 4]` | Destructive action button |
| `tab` | `normal [48,104,16,16]`, `selected [48,120,16,16]` | `[3, 3, 3, 3]` | Toggle / tab state |
| `scrollbar_track` / `scrollbar_thumb` | `[64,104,8,32]` / `[72,104,8,16]` | `[2, 2, 2, 2]` | List scrollbar |
| `slot_frame` | `[0, 224, 32, 32]` | `[3, 3, 3, 3]` | Item / pattern preview box |
| `icon.*` | 12×12 icons at `y=176` & `y=192` | — | Toolbar & page-type icons |
