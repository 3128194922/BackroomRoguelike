#!/usr/bin/env python3
"""Convert the source StructureTemplate NBTs into runtime room templates."""

from __future__ import annotations

import argparse
import gzip
import io
import json
import struct
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO


@dataclass
class Tag:
    kind: int
    name: str
    value: Any


class NbtError(ValueError):
    pass


def read_exact(stream: BinaryIO, count: int) -> bytes:
    data = stream.read(count)
    if len(data) != count:
        raise NbtError(f"unexpected end of NBT while reading {count} bytes")
    return data


def decode_mutf8(data: bytes) -> str:
    units: list[int] = []
    index = 0
    while index < len(data):
        first = data[index]
        if first & 0x80 == 0:
            units.append(first)
            index += 1
        elif first & 0xE0 == 0xC0:
            if index + 1 >= len(data):
                raise NbtError("truncated modified UTF-8 sequence")
            units.append(((first & 0x1F) << 6) | (data[index + 1] & 0x3F))
            index += 2
        elif first & 0xF0 == 0xE0:
            if index + 2 >= len(data):
                raise NbtError("truncated modified UTF-8 sequence")
            units.append(((first & 0x0F) << 12) | ((data[index + 1] & 0x3F) << 6) | (data[index + 2] & 0x3F))
            index += 3
        else:
            raise NbtError("invalid modified UTF-8 sequence")
    return b"".join(struct.pack(">H", unit) for unit in units).decode("utf-16-be", "surrogatepass")


def encode_mutf8(value: str) -> bytes:
    encoded = bytearray()
    raw = value.encode("utf-16-be", "surrogatepass")
    for index in range(0, len(raw), 2):
        unit = (raw[index] << 8) | raw[index + 1]
        if 0x0001 <= unit <= 0x007F:
            encoded.append(unit)
        elif unit <= 0x07FF:
            encoded.extend((0xC0 | (unit >> 6), 0x80 | (unit & 0x3F)))
        else:
            encoded.extend((0xE0 | (unit >> 12), 0x80 | ((unit >> 6) & 0x3F), 0x80 | (unit & 0x3F)))
    return bytes(encoded)


def read_string(stream: BinaryIO) -> str:
    length = struct.unpack(">H", read_exact(stream, 2))[0]
    return decode_mutf8(read_exact(stream, length))


def read_payload(stream: BinaryIO, kind: int, name: str = "") -> Tag:
    if kind == 1:
        value = struct.unpack(">b", read_exact(stream, 1))[0]
    elif kind == 2:
        value = struct.unpack(">h", read_exact(stream, 2))[0]
    elif kind == 3:
        value = struct.unpack(">i", read_exact(stream, 4))[0]
    elif kind == 4:
        value = struct.unpack(">q", read_exact(stream, 8))[0]
    elif kind == 5:
        value = struct.unpack(">f", read_exact(stream, 4))[0]
    elif kind == 6:
        value = struct.unpack(">d", read_exact(stream, 8))[0]
    elif kind == 7:
        length = struct.unpack(">i", read_exact(stream, 4))[0]
        if length < 0:
            raise NbtError("negative byte array length")
        value = read_exact(stream, length)
    elif kind == 8:
        value = read_string(stream)
    elif kind == 9:
        child_kind = struct.unpack(">B", read_exact(stream, 1))[0]
        length = struct.unpack(">i", read_exact(stream, 4))[0]
        if length < 0:
            raise NbtError("negative list length")
        value = [read_payload(stream, child_kind) for _ in range(length)]
    elif kind == 10:
        value = []
        while True:
            child_kind = struct.unpack(">B", read_exact(stream, 1))[0]
            if child_kind == 0:
                break
            if child_kind > 12:
                raise NbtError(f"unknown NBT tag type {child_kind}")
            value.append(read_payload(stream, child_kind, read_string(stream)))
    elif kind == 11:
        length = struct.unpack(">i", read_exact(stream, 4))[0]
        if length < 0:
            raise NbtError("negative int array length")
        value = list(struct.unpack(f">{length}i", read_exact(stream, 4 * length)))
    elif kind == 12:
        length = struct.unpack(">i", read_exact(stream, 4))[0]
        if length < 0:
            raise NbtError("negative long array length")
        value = list(struct.unpack(f">{length}q", read_exact(stream, 8 * length)))
    else:
        raise NbtError(f"invalid NBT payload type {kind}")
    return Tag(kind, name, value)


def write_string(stream: BinaryIO, value: str) -> None:
    data = encode_mutf8(value)
    if len(data) > 65535:
        raise NbtError("NBT string exceeds 65535 encoded bytes")
    stream.write(struct.pack(">H", len(data)))
    stream.write(data)


def write_payload(stream: BinaryIO, tag: Tag) -> None:
    kind, value = tag.kind, tag.value
    if kind == 1:
        stream.write(struct.pack(">b", value))
    elif kind == 2:
        stream.write(struct.pack(">h", value))
    elif kind == 3:
        stream.write(struct.pack(">i", value))
    elif kind == 4:
        stream.write(struct.pack(">q", value))
    elif kind == 5:
        stream.write(struct.pack(">f", value))
    elif kind == 6:
        stream.write(struct.pack(">d", value))
    elif kind == 7:
        stream.write(struct.pack(">i", len(value)))
        stream.write(value)
    elif kind == 8:
        write_string(stream, value)
    elif kind == 9:
        child_kind = value[0].kind if value else 0
        if any(child.kind != child_kind for child in value):
            raise NbtError(f"mixed tag types in list {tag.name}")
        stream.write(struct.pack(">Bi", child_kind, len(value)))
        for child in value:
            write_payload(stream, child)
    elif kind == 10:
        for child in value:
            stream.write(struct.pack(">B", child.kind))
            write_string(stream, child.name)
            write_payload(stream, child)
        stream.write(b"\x00")
    elif kind == 11:
        stream.write(struct.pack(">i", len(value)))
        if value:
            stream.write(struct.pack(f">{len(value)}i", *value))
    elif kind == 12:
        stream.write(struct.pack(">i", len(value)))
        if value:
            stream.write(struct.pack(f">{len(value)}q", *value))
    else:
        raise NbtError(f"cannot write NBT tag type {kind}")


def read_nbt(path: Path) -> tuple[Tag, bool]:
    raw = path.read_bytes()
    compressed = raw.startswith(b"\x1f\x8b")
    if compressed:
        raw = gzip.decompress(raw)
    stream = io.BytesIO(raw)
    kind = struct.unpack(">B", read_exact(stream, 1))[0]
    if kind != 10:
        raise NbtError(f"{path}: root tag is {kind}, expected compound")
    root = read_payload(stream, kind, read_string(stream))
    if stream.read(1):
        raise NbtError(f"{path}: trailing bytes after root compound")
    return root, compressed


def write_nbt(path: Path, root: Tag, compressed: bool) -> None:
    stream = io.BytesIO()
    stream.write(b"\x0a")
    write_string(stream, root.name)
    write_payload(stream, root)
    data = stream.getvalue()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(data, mtime=0) if compressed else data)


def child(tag: Tag, name: str) -> Tag:
    if tag.kind != 10:
        raise NbtError(f"{tag.name} is not a compound")
    for entry in tag.value:
        if entry.name == name:
            return entry
    raise NbtError(f"missing NBT field {name} in {tag.name or '<root>'}")


def maybe_child(tag: Tag, name: str) -> Tag | None:
    if tag.kind != 10:
        return None
    return next((entry for entry in tag.value if entry.name == name), None)


def get_int_list(tag: Tag) -> list[int]:
    if tag.kind != 9:
        raise NbtError(f"{tag.name} is not a list")
    return [int(entry.value) for entry in tag.value]


def collect_palette_names(root: Tag) -> dict[int, str]:
    palette = child(root, "palette")
    if palette.kind != 9:
        raise NbtError("palette is not a list")
    states: dict[int, str] = {}
    for index, state in enumerate(palette.value):
        states[index] = child(state, "Name").value
    return states


def inspect_structure(path: Path) -> dict[str, Any]:
    root, compressed = read_nbt(path)
    names = collect_palette_names(root)
    blocks = child(root, "blocks").value
    xs, ys, zs = [], [], []
    names_used: dict[str, int] = {}
    block_entities = []
    jigsaws = []
    for block in blocks:
        pos = get_int_list(child(block, "pos"))
        xs.append(pos[0]); ys.append(pos[1]); zs.append(pos[2])
        name = names[int(child(block, "state").value)]
        names_used[name] = names_used.get(name, 0) + 1
        block_nbt = maybe_child(block, "nbt")
        if block_nbt:
            block_entities.append({"pos": pos, "id": (maybe_child(block_nbt, "id").value if maybe_child(block_nbt, "id") else None)})
            if name == "minecraft:jigsaw":
                jigsaws.append({
                    "pos": pos,
                    "state": state_properties(root, int(child(block, "state").value)),
                    "nbt": to_plain(block_nbt),
                })
    return {
        "path": str(path), "compressed": compressed,
        "size": get_int_list(child(root, "size")),
        "palette_size": len(names), "block_count": len(blocks),
        "bbox": ([min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)]) if xs else None,
        "blocks_by_name": names_used,
        "block_entities": block_entities,
        "jigsaws": jigsaws,
    }


def state_properties(root: Tag, palette_index: int) -> dict[str, str]:
    palette = child(root, "palette").value
    props = maybe_child(palette[palette_index], "Properties")
    if props is None:
        return {}
    return {entry.name: entry.value for entry in props.value}


def to_plain(tag: Tag) -> Any:
    if tag.kind == 10:
        return {entry.name: to_plain(entry) for entry in tag.value}
    if tag.kind == 9:
        return [to_plain(entry) for entry in tag.value]
    if tag.kind == 7:
        return list(tag.value)
    return tag.value


def named(compound: Tag, name: str) -> Tag:
    return child(compound, name)


def int_list(values: list[int], name: str = "") -> Tag:
    return Tag(9, name, [Tag(3, "", value) for value in values])


def string(value: str, name: str = "") -> Tag:
    return Tag(8, name, value)


def clone(tag: Tag) -> Tag:
    if tag.kind == 10:
        return Tag(10, tag.name, [clone(entry) for entry in tag.value])
    if tag.kind == 9:
        return Tag(9, tag.name, [clone(entry) for entry in tag.value])
    if tag.kind == 7:
        return Tag(7, tag.name, bytes(tag.value))
    if tag.kind in (11, 12):
        return Tag(tag.kind, tag.name, list(tag.value))
    return Tag(tag.kind, tag.name, tag.value)


def set_child(compound: Tag, name: str, replacement: Tag) -> None:
    for index, entry in enumerate(compound.value):
        if entry.name == name:
            replacement.name = name
            compound.value[index] = replacement
            return
    replacement.name = name
    compound.value.append(replacement)


def mapped_state(state: Tag) -> Tag:
    # Preserve the source registry IDs, including blocks supplied by KubeJS.
    return clone(state)


def coordinates(block: Tag) -> tuple[int, int, int]:
    return tuple(get_int_list(named(block, "pos")))


def set_position(block: Tag, pos: tuple[int, int, int]) -> None:
    set_child(block, "pos", int_list(list(pos), "pos"))


def block_state_name(block: Tag, palette: list[Tag]) -> str:
    return named(palette[int(named(block, "state").value)], "Name").value


def replace_state(block: Tag, state_index: int) -> None:
    set_child(block, "state", Tag(3, "state", state_index))


def state_key(state: Tag) -> str:
    return json.dumps(to_plain(state), sort_keys=True, ensure_ascii=False)


def mapped_palette(root: Tag) -> tuple[list[Tag], dict[str, int]]:
    result: list[Tag] = []
    indices: dict[str, int] = {}
    for state in named(root, "palette").value:
        mapped = mapped_state(state)
        key = state_key(mapped)
        if key not in indices:
            indices[key] = len(result)
            result.append(mapped)
    return result, indices


def jigsaw_replacement(block: Tag, palette: list[Tag]) -> Tag | None:
    block_nbt = maybe_child(block, "nbt")
    if block_nbt is None:
        return None
    final_state = maybe_child(block_nbt, "final_state")
    if final_state is None:
        return None
    # Vanilla final_state is a block-state string; source templates use simple block IDs.
    block_id = final_state.value.split("[", 1)[0]
    state = Tag(10, "", [Tag(8, "Name", block_id)])
    return state


def normalise_template(root: Tag, *, keep_password_socket: bool = False) -> tuple[Tag, dict[str, int]]:
    source_size = get_int_list(named(root, "size"))
    if source_size[0] < 17 or source_size[2] < 17:
        raise NbtError(f"expected source room at least 17x17 horizontally, found {source_size}")
    # Remove the outer ring from the 17x17 source. The room occupies 15x15
    # blocks; keep one extra row and column on its y=0 foundation so the
    # structure/foundation remains 16x16. Source doors at coordinate 8 become
    # coordinate 7, the center of the smaller room. Placement itself is origin-aligned.
    crop_min = 1
    crop_room_max = 16
    crop_foundation_max = 17
    source_palette = named(root, "palette").value
    palette, palette_indices = mapped_palette(root)
    jigsaw_states: dict[str, int] = {}
    kept: list[Tag] = []
    for original in named(root, "blocks").value:
        block = clone(original)
        x, y, z = coordinates(block)
        crop_max = crop_foundation_max if y == 0 else crop_room_max
        if not (crop_min <= x < crop_max and crop_min <= z < crop_max):
            continue
        set_position(block, (x - crop_min, y, z - crop_min))
        source_name = named(source_palette[int(named(block, "state").value)], "Name").value
        # The source files embed ten layers of temporary bedrock below the usable room.
        # The new dimension owns its sole boundary at min_y, so retaining that filler
        # would seal the upper half of every surface-anchored room.
        if source_name == "minecraft:bedrock":
            continue
        if source_name == "minecraft:jigsaw":
            block_nbt = maybe_child(block, "nbt")
            target = maybe_child(block_nbt, "target") if block_nbt else None
            if keep_password_socket and target and target.value == "backroom:password_lock":
                kept.append(block)
                continue
            replacement = jigsaw_replacement(block, source_palette)
            if replacement is None:
                continue
            replacement = mapped_state(replacement)
            key = state_key(replacement)
            if key not in palette_indices:
                palette_indices[key] = len(palette)
                palette.append(replacement)
            replace_state(block, palette_indices[key])
            # Jigsaw control block entities are never copied into runtime templates.
            block.value = [entry for entry in block.value if entry.name != "nbt"]
        else:
            mapped = mapped_state(source_palette[int(named(block, "state").value)])
            key = state_key(mapped)
            replace_state(block, palette_indices[key])
        kept.append(block)
    set_child(root, "size", int_list([16, source_size[1], 16], "size"))
    set_child(root, "palette", Tag(9, "palette", palette))
    set_child(root, "blocks", Tag(9, "blocks", kept))
    return root, palette_indices


def room_jigsaws(root: Tag) -> list[Tag]:
    palette = named(root, "palette").value
    return [block for block in named(root, "blocks").value if block_state_name(block, palette) == "minecraft:jigsaw"]


def socket_block(root: Tag) -> Tag:
    matches = []
    palette = named(root, "palette").value
    for block in named(root, "blocks").value:
        nbt = maybe_child(block, "nbt")
        if block_state_name(block, palette) == "minecraft:jigsaw" and nbt and maybe_child(nbt, "target") and maybe_child(nbt, "target").value == "backroom:password_lock":
            matches.append(block)
    if len(matches) != 1:
        raise NbtError(f"password_room.nbt should contain exactly one password_lock socket, found {len(matches)}")
    return matches[0]


def orientation_for(root: Tag, block: Tag) -> str:
    palette = named(root, "palette").value
    props = maybe_child(palette[int(named(block, "state").value)], "Properties")
    orientation = maybe_child(props, "orientation") if props else None
    return orientation.value if orientation else "south_up"


def rotate_lock_pos(x: int, y: int, z: int, orientation: str) -> tuple[int, int, int]:
    # The jigsaw front points from the socket into the room; the second template axis is aligned to world up.
    # Place the 1x2x2 lock face against that opening while keeping its vertical axis upright.
    facing = orientation.split("_")[-1]
    if facing == "north":
        return x, y, z
    if facing == "south":
        return -x, y, -z
    if facing == "east":
        return -z, y, x
    if facing == "west":
        return z, y, -x
    raise NbtError(f"unsupported jigsaw orientation {orientation}")


def merge_lock(room: Tag, lock_path: Path) -> None:
    lock, _ = read_nbt(lock_path)
    socket = socket_block(room)
    socket_pos = coordinates(socket)
    orientation = orientation_for(room, socket)
    palette = named(room, "palette").value
    palette_indices = {state_key(state): index for index, state in enumerate(palette)}
    lock_palette = named(lock, "palette").value
    blocks = named(room, "blocks").value
    socket_nbt = maybe_child(socket, "nbt")
    final_state = maybe_child(socket_nbt, "final_state") if socket_nbt else None
    if final_state:
        name = final_state.value.split("[", 1)[0]
        replacement = Tag(10, "", [Tag(8, "Name", name)])
        key = state_key(replacement)
        if key not in palette_indices:
            palette_indices[key] = len(palette); palette.append(replacement)
        replace_state(socket, palette_indices[key])
        socket.value = [entry for entry in socket.value if entry.name != "nbt"]
    for original in named(lock, "blocks").value:
        source_name = block_state_name(original, lock_palette)
        if source_name == "minecraft:jigsaw":
            continue
        x, y, z = coordinates(original)
        dx, dy, dz = rotate_lock_pos(x, y, z, orientation)
        pos = (socket_pos[0] + dx, socket_pos[1] + dy, socket_pos[2] + dz)
        size = get_int_list(named(room, "size"))
        if not (0 <= pos[0] < size[0] and 0 <= pos[2] < size[2]):
            continue
        block = clone(original)
        set_position(block, pos)
        state = mapped_state(lock_palette[int(named(block, "state").value)])
        key = state_key(state)
        if key not in palette_indices:
            palette_indices[key] = len(palette); palette.append(state)
        replace_state(block, palette_indices[key])
        blocks.append(block)
    set_child(room, "palette", Tag(9, "palette", palette))
    set_child(room, "blocks", Tag(9, "blocks", blocks))


def replace_socket_with_extraction(root: Tag) -> None:
    socket = socket_block(root)
    palette = named(root, "palette").value
    palette_indices = {state_key(state): index for index, state in enumerate(palette)}
    extraction = Tag(10, "", [Tag(8, "Name", "backroomroguelike:extraction_point")])
    key = state_key(extraction)
    if key not in palette_indices:
        palette_indices[key] = len(palette); palette.append(extraction)
    replace_state(socket, palette_indices[key])
    socket.value = [entry for entry in socket.value if entry.name != "nbt"]
    set_child(root, "palette", Tag(9, "palette", palette))


def generate(input_dir: Path, lock_dir: Path, output_dir: Path) -> None:
    source_rooms = sorted(path for path in input_dir.glob("*.nbt") if path.name not in {"password_room.nbt", "sweet_roll.nbt"})
    if len(source_rooms) != 18:
        raise NbtError(f"expected 18 ordinary rooms (excluding password_room and sweet_roll), found {len(source_rooms)}")
    locks = sorted(lock_dir.glob("lock_*.nbt"), key=lambda path: int(path.stem.split("_")[1]))
    if [path.stem for path in locks] != [f"lock_{index}" for index in range(1, 40)]:
        raise NbtError("expected lock_1.nbt through lock_39.nbt under the lock input directory")
    for source in source_rooms:
        room, compressed = read_nbt(source)
        room, _ = normalise_template(room, keep_password_socket=True)
        write_nbt(output_dir / source.name, room, compressed)
    base_path = input_dir / "password_room.nbt"
    for index, lock_path in enumerate(locks, 1):
        room, compressed = read_nbt(base_path)
        room, _ = normalise_template(room, keep_password_socket=True)
        merge_lock(room, lock_path)
        write_nbt(output_dir / "password" / f"lock_{index:02d}.nbt", room, compressed)
    extraction, compressed = read_nbt(base_path)
    extraction, _ = normalise_template(extraction, keep_password_socket=True)
    replace_socket_with_extraction(extraction)
    write_nbt(output_dir / "password" / "extraction_room.nbt", extraction, compressed)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("structures_old"))
    parser.add_argument("--locks", type=Path, default=Path("structures/locks"), help="directory containing lock_1.nbt through lock_39.nbt")
    parser.add_argument("--output", type=Path, default=Path("src/main/resources/data/backroomroguelike/structures/rooms"))
    parser.add_argument("--inspect", action="store_true", help="print summaries of input NBT files; do not generate outputs")
    args = parser.parse_args()
    if args.inspect:
        paths = sorted(args.input.rglob("*.nbt"))
        for path in paths:
            try:
                print(json.dumps(inspect_structure(path), ensure_ascii=False))
            except Exception as exc:
                print(f"ERROR {path}: {exc}", file=sys.stderr)
                return 1
        return 0
    generate(args.input, args.locks, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
