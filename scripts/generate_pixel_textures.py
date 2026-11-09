#!/usr/bin/env python3
"""
Generate 16x16 pixel art textures for Truth Crystal items and Pedestal block.
"""
from PIL import Image
import os

ASSETS_DIR = "/home/antigravity/xm1221/AbadonedGreatwork/common/src/main/resources/assets/abadoned_greatwork/textures"
ITEM_DIR = os.path.join(ASSETS_DIR, "item")
BLOCK_DIR = os.path.join(ASSETS_DIR, "block")

def grid_to_image(grid, palette):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        row = grid[y]
        for x in range(16):
            ch = row[x]
            if ch in palette:
                img.putpixel((x, y), palette[ch])
    return img

# ==================== Truth Crystal 0 (Apprentice - Aventurine/Cyan) ====================
tc0_palette = {
    "#": (14, 42, 48, 255),    # Outline dark
    "o": (24, 76, 86, 255),    # Shading deep teal
    "b": (38, 122, 132, 255),  # Base teal
    "c": (58, 168, 178, 255),  # Medium cyan
    "l": (102, 214, 220, 255), # Bright cyan
    "g": (184, 244, 248, 255), # Pale glow
    "w": (255, 255, 255, 255), # Specular highlight
}

tc0_grid = [
    "........##......",
    ".......#lw#.....",
    "......#lgcw#....",
    ".....#lggccw#...",
    "....#lggcclcw#..",
    "...#lggcclcccw#.",
    "...#lgcclccccw#.",
    "...#lgcclccccw#.",
    "...#lgcclccccw#.",
    "....#gcclcccw#..",
    "....#occlcbo#...",
    ".....#ocbbo#....",
    "......#obo#.....",
    ".......#o#......",
    "........#.......",
    "................",
]

# ==================== Truth Crystal 1 (Advanced - Citrine/Amber/Gold) ====================
tc1_palette = {
    "#": (54, 30, 8, 255),     # Outline dark bronze
    "o": (110, 64, 14, 255),   # Shading deep amber
    "b": (172, 112, 20, 255),  # Base amber
    "c": (222, 158, 30, 255),  # Medium gold
    "l": (246, 202, 60, 255),  # Bright gold
    "g": (255, 240, 144, 255), # Pale gold gleam
    "w": (255, 255, 255, 255), # Specular highlight
}

tc1_grid = [
    "........##......",
    ".......#lw#.....",
    "......#lgcw#....",
    "..##..#lgccw#..##",
    ".#lw##lggcclcw##lw#",
    "#lgcclggcclcccwccw#",
    "#lgcclgcclccccwccw#",
    "#lgcc#lgcclccc#ccc#",
    ".#bc#lgcclccccw#cb#",
    "..#o#lgcclccccw#o#.",
    "...#.#gcclcccw#.#..",
    "....#occlcbo#...",
    ".....#ocbbo#....",
    "......#obo#.....",
    ".......#o#......",
    "........#.......",
]

# ==================== Truth Crystal 2 (Master - Amethyst/Astral Violet) ====================
tc2_palette = {
    "#": (28, 10, 46, 255),    # Outline astral void
    "o": (62, 22, 96, 255),    # Shading deep purple
    "b": (114, 42, 168, 255),  # Base amethyst
    "c": (164, 74, 220, 255),  # Medium vibrant violet
    "l": (208, 130, 244, 255), # Bright electric lavender
    "g": (242, 210, 255, 255), # Mystic pale glow
    "w": (255, 255, 255, 255), # Specular highlight
    "*": (220, 160, 255, 200), # Floating particle sparks
}

tc2_grid = [
    "........##......",
    "...*...#lw#...*.",
    "......#lgcw#....",
    ".....#lggccw#...",
    ".*..#lggcclcw#..",
    "...#lggcclcccw#.",
    "..#lggcclccccw#*",
    "..#lgcclcgcccw#.",
    "*..#lgcllcgcccw#",
    "...#ogcclcgccw#.",
    "....#occlcbo#...",
    "..*..#ocbbo#..*.",
    "......#obo#.....",
    ".......#o#......",
    "...*....#.......",
    "................",
]

# ==================== Truth Crystal Pedestal Block Texture (16x16) ====================
block_palette = {
    "D": (32, 28, 40, 255),   # Dark slate shadow
    "S": (46, 42, 54, 255),   # Slate base dark
    "M": (62, 58, 74, 255),   # Slate medium
    "L": (82, 76, 96, 255),   # Slate light edge
    "H": (108, 100, 124, 255),# Slate highlight bevel
    "c": (38, 138, 158, 255), # Dark cyan rune channel
    "C": (74, 208, 224, 255), # Bright cyan rune light
    "W": (180, 245, 255, 255),# White-cyan core rune
    "p": (130, 48, 192, 255), # Purple amethyst glyph
    "P": (186, 98, 246, 255), # Bright amethyst glow
}

# The block texture uses slate stone borders with an inlaid glowing runic circle and glyphs
block_grid = [
    "HHHHHHHHHHHHHHHH",
    "HLLLLLLLLLLLLLLD",
    "HLSSSSSSMMSSSSLD",
    "HLSSccCCCCccSSLD",
    "HLSScCWWWWCcSSLD",
    "HLScCWPppPWCcsLD",
    "HLScCWPppPWCcsLD",
    "HLSCCPpWWpPCCsLD",
    "HLSCCPpWWpPCCsLD",
    "HLScCWPppPWCcsLD",
    "HLScCWPppPWCcsLD",
    "HLSScCWWWWCcSSLD",
    "HLSSccCCCCccSSLD",
    "HLSSSSMMMMSSSSLD",
    "HDDDDDDDDDDDDDDD",
    "DDDDDDDDDDDDDDDD",
]

# Generate and save images
img0 = grid_to_image(tc0_grid, tc0_palette)
img0.save(os.path.join(ITEM_DIR, "truth_crystal_0.png"))
print("Saved 16x16 truth_crystal_0.png")

img1 = grid_to_image(tc1_grid, tc1_palette)
img1.save(os.path.join(ITEM_DIR, "truth_crystal_1.png"))
print("Saved 16x16 truth_crystal_1.png")

img2 = grid_to_image(tc2_grid, tc2_palette)
img2.save(os.path.join(ITEM_DIR, "truth_crystal_2.png"))
print("Saved 16x16 truth_crystal_2.png")

img_block = grid_to_image(block_grid, block_palette)
img_block.save(os.path.join(BLOCK_DIR, "truth_crystal.png"))
print("Saved 16x16 block/truth_crystal.png")
