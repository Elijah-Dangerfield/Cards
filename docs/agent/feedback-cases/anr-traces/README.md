# ANR traces, 2026-09-03

Three ANR traces pulled from Play Console. Kept verbatim because together they are what closed
ENG-49's causation question, and re-deriving that from a summary would be harder than reading them.

Every one has a **byte-identical RenderThread stack**, stuck in Skia's GPU glyph cache:

```
GrTextBlobRedrawCoordinator::internalRemove
GrTextBlobRedrawCoordinator::drawGlyphRunList
  ... → SkCanvas::drawTextBlob → 13 nested RenderNodeDrawable levels
```

The filenames record what the **main** thread was doing, which is different in each:

| File | `main` was blocked in |
|---|---|
| `2026-09-03-anr-dialog-show.log` | `Dialog.show` → `ThreadedRenderer.create` → `nCreateProxy` |
| `2026-09-03-anr-dialog-dismiss.log` | `Dialog.dismiss` → `destroyHardwareResources` |
| `2026-09-03-anr-plain-frame.log` | `performTraversals` → `nSyncAndDrawFrame` — **no dialog involved** |

That third one is the important file. It shows the stall with no bottom sheet anywhere near it,
which is what disproves the original (wrong) diagnosis that mounting a `ModalBottomSheet` was the
cause. The sheet is a frequent caller and therefore a frequent victim, nothing more.

Analysis: `docs/plans/renderthread-text-stall.md`. Earlier event: `../CARDS-C1.md`.
