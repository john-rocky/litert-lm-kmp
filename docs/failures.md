# Failures

The lower level throws one sealed class, with the same types on every platform:

| `LiteRtLmException` | When | What to do |
|---|---|---|
| `ModelNotFound` | No file at the path | Download it, or fix the path |
| `InvalidModel` | A directory, not a `.litertlm` file, or a file cut short | Download it again |
| `InsufficientMemory` | Less memory free than `minAvailableMemoryBytes`, or on iOS too little address space | Close other apps and your other engines, or use a smaller model |
| `InputTooLong` | The input does not fit in `maxNumTokens`, counting earlier turns | Shorten it, or start a new conversation |
| `Cancelled` | `cancelProcess()` or a close stopped the turn | The next turn works |
| `BackendUnavailable` | The backend cannot run this model here, or an image came without `visionBackend` | Use the CPU, or set `visionBackend` |
| `InvalidImage` | The bytes are not an image the runtime can decode | Send a JPEG or PNG |
| `ClosedUse`, `NotInitialized`, `AlreadyInitialized` | Calls out of order | Fix the order |
| `Internal` | On iOS, the app does not have the Swift package ([iOS](install.md#ios)), with `code` 9. Otherwise anything else the runtime reported, with its status code in `code`. | Read the message |

The task API throws `GenAiException`, with the numbers ML Kit uses. A provider's own failure is the `cause`:

| `errorCode` | When |
|---|---|
| `NOT_AVAILABLE` (8) | The model file is missing or is not a model, or too little memory is free. Gemini Nano on iOS or the JVM, or in an Android app without `litertlm-genai-nano`: the message gives the line to add. A download that fails reports this to `onDownloadFailed`, with the host or the Hub's status in the message. |
| `REQUEST_TOO_LARGE` (12) | The input does not fit. `setLongInputAutoTruncationEnabled(true)` on a summarizer keeps the start instead. |
| `REQUEST_TOO_SMALL` (-100) | An article under 400 characters, or blank text |
| `NOT_SUPPORTED` (16) | The backend cannot run this model here, an image on a model without a vision part, or `countTokens` on a local model |
| `INVALID_INPUT_IMAGE` (-102) | The bytes are not an image the runtime can decode |
| `CANCELLED` (7) | `close()` stopped the request |
| `BACKGROUND_USE_BLOCKED` (30), `BUSY` (9) | From Gemini Nano. See [Who answers](who-answers.md). |
| `NOT_ENOUGH_DISK_SPACE` (501) | A download ran out of disk |

`BUSY` and `NOT_ENOUGH_DISK_SPACE` have not come up on a phone, and `Internal` only for a missing Swift package. The other cases have, and [`docs/tested-devices.md`](tested-devices.md) has the list.
