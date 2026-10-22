#!/usr/bin/env python3
"""Checks the pictures of the tour by what they must be true of, not by what
they must look like.

The tour's text output says where things are and what they are called. It
cannot say whether anything was actually drawn there: the accessibility tree
has no colour in it, so a theme that never applied, a glyph that failed to
render and text the same colour as its background all look perfectly fine to
a name-and-bounds dump.

This script reads the pixels and asks three questions that can be answered
without knowing what the app is supposed to look like:

  * is the picture blank?            a screen with no ink in it is a failure
  * do the themes differ from each other?   five themes must give five pictures
  * is there enough contrast?        text must stand out from its background

The checks are on properties (distinct, blank, ratio), never on exact colours,
so a different emulator, GPU or font cannot make them fail for the wrong
reason.

Usage:
    check-pixels.py [--themes] <directory of pictures> [more directories...]

Every directory is checked on its own. With --themes the pictures in the
directory must also differ from each other, which is what a run that took the
same screen in every theme has to show. Give that flag only for such a run: a
walk through the whole app takes pictures of different screens, and two
screens are allowed to have the same colours.

Exit codes: 0 all is well, 1 something that matters is wrong.
"""

import os
import struct
import sys
import zlib

# How much the brightness varies across a screen. A screen with text and icons
# varies a lot; a screen where nothing was drawn is flat and sits at 0.
MIN_SPREAD = 3.0

# A picture with fewer colours than this is very likely to have no text on it.
# It is only reported, because two colours (background and one line of text) is
# a perfectly honest screen.
FEW_COLOURS = 4

# Two pictures of the same screen in two themes must differ in at least this
# share of their pixels. Two themes that merely look alike still differ almost
# everywhere, while a theme that was never applied changes nothing.
SAME_PICTURE = 0.02

# WCAG calls 3:1 the least contrast for large text and 4.5:1 for body text.
# The check only insists on the lowest bar, because the point is to catch text
# that is invisible, not to grade the design.
MIN_CONTRAST = 3.0


# --------------------------------------------------------------------------
# Reading a PNG with nothing but the standard library
# --------------------------------------------------------------------------

class NotReadable(Exception):
    pass


def read_png(path):
    """Returns (width, height, pixels) where pixels are RGBA bytes."""
    try:
        data = open(path, 'rb').read()
    except OSError as e:
        raise NotReadable('cannot be opened (%s)' % e)
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise NotReadable('not a PNG file')

    pos = 8
    header = None
    idat = b''
    while pos < len(data):
        if pos + 8 > len(data):
            break
        length = struct.unpack('>I', data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b'IHDR':
            header = struct.unpack('>IIBBBBB', body[:13])
        elif kind == b'IDAT':
            idat += body
        elif kind == b'IEND':
            break

    if header is None:
        raise NotReadable('no header')
    # struct.unpack already gives whole numbers, so nothing needs converting.
    width, height, depth, colour = header[0], header[1], header[2], header[3]
    interlace = header[6]

    if depth != 8:
        raise NotReadable('only 8 bits per channel is handled, not %d' % depth)
    if interlace != 0:
        raise NotReadable('interlaced pictures are not handled')

    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(colour, 0)
    if channels == 0:
        raise NotReadable('unknown colour type %d' % colour)

    try:
        raw = zlib.decompress(idat)
    except zlib.error as e:
        raise NotReadable('the picture data is broken (%s)' % e)
    stride = width * channels
    out = bytearray(width * height * 4)
    previous = bytearray(stride)
    i = 0
    for y in range(height):
        if i >= len(raw):
            raise NotReadable('the picture ends early')
        filter_type = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        # The five filter types of the format. Each byte is stored as the
        # difference from what the same filter works out, so it is undone by
        # adding it back.
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = previous[x]
            c = previous[x - channels] if x >= channels else 0
            if filter_type == 1:
                line[x] = (line[x] + a) & 255
            elif filter_type == 2:
                line[x] = (line[x] + b) & 255
            elif filter_type == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif filter_type == 4:
                pa = abs(b - c)
                pb = abs(a - c)
                pc = abs(a + b - 2 * c)
                if pa <= pb and pa <= pc:
                    guess = a
                elif pb <= pc:
                    guess = b
                else:
                    guess = c
                line[x] = (line[x] + guess) & 255
            elif filter_type != 0:
                raise NotReadable('unknown filter %d' % filter_type)

        row = y * width * 4
        if channels == 4:
            out[row:row + stride] = line
        elif channels == 3:
            for x in range(width):
                out[row + x * 4:row + x * 4 + 3] = line[x * 3:x * 3 + 3]
                out[row + x * 4 + 3] = 255
        elif channels == 1:
            for x in range(width):
                v = line[x]
                out[row + x * 4:row + x * 4 + 4] = bytes((v, v, v, 255))
        else:  # grey and transparent
            for x in range(width):
                v = line[x * 2]
                out[row + x * 4:row + x * 4 + 4] = bytes((v, v, v, 255))
        previous = line

    return width, height, out


def brightness(red, green, blue):
    """How bright a colour looks, weighted as the eye sees it."""
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def contrast(first, second):
    """The WCAG ratio between two brightnesses."""
    a = (first + 12.92) / 255.0 if first <= 10.4 else ((first / 255.0 + 0.055) / 1.055) ** 2.4
    b = (second + 12.92) / 255.0 if second <= 10.4 else ((second / 255.0 + 0.055) / 1.055) ** 2.4
    a *= 255.0
    b *= 255.0
    lighter, darker = max(a, b), min(a, b)
    return (lighter + 0.05) / (darker + 0.05)


def region(width, pixels, x0, y0, x1, y1, step=3):
    """The colours and brightness of a part of the picture.

    [step] samples every nth pixel, because a whole picture is more detail
    than any of these questions need.
    """
    colours = set()
    total = 0.0
    total_square = 0.0
    count = 0
    for y in range(y0, y1, step):
        base = y * width * 4
        for x in range(x0, x1, step):
            i = base + x * 4
            if i + 2 >= len(pixels):
                continue
            red, green, blue = pixels[i], pixels[i + 1], pixels[i + 2]
            colours.add((red >> 2, green >> 2, blue >> 2))
            value = brightness(red, green, blue)
            total += value
            total_square += value * value
            count += 1
    if count == 0:
        return set(), 0.0, 0.0
    mean = total / count
    spread = (max(0.0, total_square / count - mean * mean)) ** 0.5
    return colours, mean, spread


# --------------------------------------------------------------------------
# Reading the names that were on the screen, when they were written down
# --------------------------------------------------------------------------

def read_names(picture):
    """The names and boxes of the screen, if a dump was written next to it."""
    path = os.path.splitext(picture)[0] + '.txt'
    if not os.path.isfile(path):
        return []
    found = []
    try:
        lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    except OSError:
        return []
    for line in lines:
        # "New file" [933,1647][996,1710]
        if not line.startswith('"'):
            continue
        end = line.find('" ', 1)
        if end < 0:
            continue
        name = line[1:end]
        box = line[end + 2:].strip()
        numbers = box.replace(']', ' ').replace('[', ' ').replace(',', ' ').split()
        if len(numbers) != 4:
            continue
        try:
            left, top, right, bottom = (int(n) for n in numbers)
        except ValueError:
            continue
        if right > left and bottom > top:
            found.append((name, left, top, right, bottom))
    return found


# --------------------------------------------------------------------------
# The checks
# --------------------------------------------------------------------------

def check_picture(path, problems, notes):
    try:
        width, height, pixels = read_png(path)
    except NotReadable as e:
        problems.append('%s: cannot be read (%s)' % (os.path.basename(path), e))
        return None

    name = os.path.basename(path)
    colours, mean, spread = region(width, pixels, 0, 0, width, height)

    # 1. Is anything drawn at all? A screen where nothing was drawn is flat:
    #    every pixel the same, so the brightness does not vary.
    if spread < MIN_SPREAD:
        problems.append(
            '%s: the picture is flat (brightness spread %.1f, %d colours); the '
            'app may not have drawn anything'
            % (name, spread, len(colours)))
    elif len(colours) < FEW_COLOURS:
        notes.append('%s: only %d colours, which is unusually few for a screen'
                     % (name, len(colours)))

    # 2. Is there text that stands out from its background?
    darkest, lightest = 255.0, 0.0
    step = 4
    for y in range(0, height, step):
        base = y * width * 4
        for x in range(0, width, step):
            i = base + x * 4
            if i + 2 >= len(pixels):
                continue
            value = brightness(pixels[i], pixels[i + 1], pixels[i + 2])
            if value < darkest:
                darkest = value
            if value > lightest:
                lightest = value
    ratio = contrast(darkest, lightest)
    if ratio < MIN_CONTRAST:
        problems.append(
            '%s: only %.1f:1 contrast between the darkest and lightest parts; '
            'text may be invisible' % (name, ratio))

    # 3. What does each named part of the screen look like? Only reported,
    #    never a failure: a part that is one colour may simply be a container
    #    with nothing of its own drawn on it.
    flat = []
    for label, left, top, right, bottom in read_names(path):
        if right - left < 8 or bottom - top < 8:
            continue
        if right - left > 260 or bottom - top > 260:
            continue
        piece, _, _ = region(width, pixels, left, top, right, bottom, step=1)
        if len(piece) <= 1:
            flat.append(label)
    if flat:
        notes.append('%s: %d named part(s) with nothing drawn: %s'
                     % (name, len(flat), ', '.join(flat[:6])))


def difference(first, second, step=3):
    """The share of pixels that look different in two pictures of one size.

    Counting differing pixels is far more telling than comparing average
    colours: two themes that merely look similar still differ in most of their
    pixels, while a theme that was never applied gives pictures that differ in
    almost none.
    """
    width, height, a = first
    other_width, other_height, b = second
    if (width, height) != (other_width, other_height):
        return None
    different = 0
    total = 0
    for y in range(0, height, step):
        base = y * width * 4
        for x in range(0, width, step):
            i = base + x * 4
            total += 1
            if (abs(a[i] - b[i]) > 8
                    or abs(a[i + 1] - b[i + 1]) > 8
                    or abs(a[i + 2] - b[i + 2]) > 8):
                different += 1
    return different / total if total else 0.0


def check_themes(pictures, problems, notes):
    """The same screen in every theme must give a different picture each time."""
    if len(pictures) < 3:
        notes.append('fewer than three pictures, so the themes are not compared')
        return

    read = []
    for picture in pictures:
        try:
            read.append((os.path.basename(picture), read_png(picture)))
        except NotReadable as e:
            notes.append('%s: cannot be read (%s)' % (os.path.basename(picture), e))

    same = []
    for i in range(len(read)):
        for j in range(i + 1, len(read)):
            share = difference(read[i][1], read[j][1])
            if share is None:
                notes.append('%s and %s are not the same size, so they cannot '
                             'be compared' % (read[i][0], read[j][0]))
            elif share < SAME_PICTURE:
                same.append('%s and %s differ in only %.0f%% of their pixels'
                            % (read[i][0], read[j][0], share * 100))
    if same:
        problems.append(
            'the themes did not change the screen: %s. A theme changes the '
            'colours of the same screen, so two themes must give two '
            'different pictures.' % '; '.join(same))

    # The palest theme and the darkest are far apart in brightness, so the set
    # as a whole should cover a wide range. Reported, not failed on: a tour of
    # only the light themes is a legitimate thing to do.
    brightnesses = []
    for _, (width, height, pixels) in read:
        _, mean, _ = region(width, pixels, 0, 0, width, height)
        brightnesses.append(mean)
    if brightnesses:
        notes.append(
            '%d theme pictures, brightness from %.0f to %.0f (span %.0f)'
            % (len(brightnesses), min(brightnesses), max(brightnesses),
               max(brightnesses) - min(brightnesses)))


def main(argv):
    # The theme check only makes sense for a run that took a picture of the
    # SAME screen in every theme. A walk through the whole app takes pictures
    # of different screens, which are allowed to have the same colours, so the
    # check is asked for rather than assumed.
    themes = False
    directories = []
    for argument in argv[1:]:
        if argument in ('--themes', '-t'):
            themes = True
        elif argument.startswith('-'):
            print('unknown option: %s' % argument)
            return 2
        else:
            directories.append(argument)
    if not directories:
        directories = ['.']

    failed = False

    for directory in directories:
        if not os.path.isdir(directory):
            print('not a directory: %s' % directory)
            failed = True
            continue

        try:
            names = os.listdir(directory)
        except OSError as e:
            print('::error::cannot read %s (%s)' % (directory, e))
            failed = True
            continue
        pictures = sorted(
            os.path.join(directory, f) for f in names
            if f.lower().endswith('.png'))
        print('== %s: %d picture(s)' % (directory, len(pictures)))
        if not pictures:
            print('::error::there are no pictures in %s' % directory)
            failed = True
            continue

        problems = []
        notes = []
        for picture in pictures:
            check_picture(picture, problems, notes)

        if themes:
            check_themes(pictures, problems, notes)

        for note in notes:
            print('   note: %s' % note)
        for problem in problems:
            print('::error::%s' % problem)
        if problems:
            failed = True
        elif themes:
            print('   every picture has ink in it, and the themes differ')
        else:
            print('   every picture has ink in it, and stands out from its background')

    print('')
    print('the pictures are fine' if not failed else 'the pictures have problems')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
