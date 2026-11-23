# Texture-Driven GUI Skins (`gui_skins/`)

All note GUI screens and modal dialogs load their texture atlas, 9-slice margins, state UVs, colors, and region coordinates from `assets/abadoned_greatwork/gui_skins/<name>.json`. Changes apply immediately on client resource reload (`F3+T`).

## Inheritance (`"extends"`)

- `note_common.json` defines the shared `512×256` pixel-art atlas (`textures/gui/notes/atlas.png`), all sprites, and the dark-leather + warm-parchment palette.
- Screen skins (`note_library.json`, `note_editor.json`, `note_preview.json`, `note_modals.json`) set `"extends": "note_common"` and define their layout dimensions and region rectangles `[x, y, w, h]`.

## Default Atlas Map (`textures/gui/notes/atlas.png`, 512×256)

| Sprite Key | UV `[u, v, w, h]` | Nine-Slice `[l, t, r, b]` | Purpose |
|---|---:|---:|---|
| `book_cover` / `panel` | `[0, 0, 64, 64]` | `[10, 10, 10, 10]` | Dark amethyst leather book cover |
| `book_page_left` | `[64, 0, 64, 64]` | `[10, 10, 10, 10]` | Left parchment page with inner spine fold |
| `book_page_right` / `page_paper` | `[128, 0, 64, 64]` | `[10, 10, 10, 10]` | Right parchment page with inner spine fold |
| `book_spine` | `[192, 0, 12, 64]` | `[2, 8, 2, 8]` | Stitched vertical book spine seam |
| `modal_panel` | `[208, 0, 48, 48]` | `[8, 8, 8, 8]` | Elevated modal frame |
| `sub_panel` / `paper_sub_panel` | `[0,64,32,32]` / `[32,64,32,32]` | `[4, 4, 4, 4]` | Recessed dark / parchment sub-panels |
| `paper_slot` / `dark_slot` | `[64,64,24,24]` / `[88,64,24,24]` | `[3, 3, 3, 3]` | Item / pattern display slots |
| `list_item_normal` / `list_item_selected` | `[112,64,24,24]` / `[136,64,24,24]` | `[3, 3, 3, 3]` | Scrollable list rows |
| `scrollbar_track` / `scrollbar_thumb` | `[160,64,8,32]` / `[168,64,8,16]` | `[2, 2, 2, 2]` | Draggable scrollbar |
| `card` | `normal [176,64,32,32]`, `hover [208,64,32,32]`, `selected [176,96,32,32]` | `[4, 4, 4, 4]` | Picker grid cards |
| `dark_field` / `paper_field` | `normal y=96`, `focused y=112` (`48×16`) | `[3, 3, 3, 3]` | Dark & parchment text fields |
| `button` / `button_accent` / `button_danger` | `x=0,48,96`, states at `y=128,144,160` | `[4, 4, 4, 4]` | Book chrome & modal buttons |
| `paper_button` | `x=144`, states at `y=128,144,160,176` | `[4, 4, 4, 4]` | Parchment-toned page controls |
| `page_tab` | `x=192`, `normal/selected/error` (`32×16`) | `[4, 4, 4, 4]` | Bottom page strip tabs |
| `crafting_grid` / `smelting_frame` | `[256,0,104,58]` / `[368,0,86,56]` | — | Vanilla-density recipe backdrops |
| `icon.*` / `icon.fmt.*` / `icon.page.*` | `12×12` icons at `y=128,144,160`, `x=256+` | — | Toolbar, format & page-type icons |
