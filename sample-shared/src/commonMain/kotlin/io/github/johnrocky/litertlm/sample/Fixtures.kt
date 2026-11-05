package io.github.johnrocky.litertlm.sample

import kotlin.io.encoding.Base64

/** The lines of the Gettysburg Address (public domain) that the summaries and chat_3 work on. */
internal const val SUMMARY_TEXT = """Four score and seven years ago our fathers brought forth on this continent, a new nation, conceived in Liberty, and dedicated to the proposition that all men are created equal.

Now we are engaged in a great civil war, testing whether that nation, or any nation so conceived and so dedicated, can long endure. We are met on a great battle-field of that war. We have come to dedicate a portion of that field, as a final resting place for those who here gave their lives that that nation might live. It is altogether fitting and proper that we should do this.

But, in a larger sense, we can not dedicate -- we can not consecrate -- we can not hallow -- this ground. The brave men, living and dead, who struggled here, have consecrated it, far above our poor power to add or detract. The world will little note, nor long remember what we say here, but it can never forget what they did here. It is for us the living, rather, to be dedicated here to the unfinished work which they who fought here have thus far so nobly advanced. It is rather for us to be here dedicated to the great task remaining before us -- that from these honored dead we take increased devotion to that cause for which they gave the last full measure of devotion -- that we here highly resolve that these dead shall not have died in vain -- that this nation, under God, shall have a new birth of freedom -- and that government of the people, by the people, for the people, shall not perish from the earth."""

internal val SUMMARY_WORDS = listOf("nation", "war", "freedom", "liberty", "dedicat", "dead")

/**
 * What the screen starts with, and the streamed cases. Not [SUMMARY_TEXT]: on a Galaxy S26, nano-v3 stops a streamed summary
 * of that one with error code 11, "Generated response doesn't pass certain policy check".
 */
internal const val ARTICLE_TEXT = """The town library opened a new reading room this spring. The room faces the river and has long tables, soft chairs and shelves of local history. Volunteers repainted the walls and built a small corner for children, with picture books and a rug shaped like a boat.

On Saturday mornings a retired teacher reads stories aloud, and on weekday evenings students come to study until closing time. The library plans to add more seats next year and to keep the doors open one hour later."""

internal const val CONVERSATION_TEXT = """Maya: Are we still on for the demo on Thursday?
Sam: The backend is not ready, so I would push it to Monday.
Maya: Monday works for me, but the client flies out on Friday.
Sam: Then we could record the demo and send it to them on Friday.
Maya: Good idea. I will tell the client about the new plan today.
Sam: And I will finish the backend over the weekend.
Maya: Let us do a dry run on Monday morning.
Sam: Sounds good, I will book the room."""

internal val CONVERSATION_WORDS = listOf("launch", "demo", "friday", "thursday", "monday")

internal const val PROOFREAD_TEXT =
    "Please recieve the documents befor the meeting on Friday and let me no if anything is missing."

internal const val SHORTEN_TEXT = "I wanted to let you know that, after giving it a great deal of careful thought over the " +
    "past several days, I have come to the conclusion that we should probably move the team meeting from Tuesday " +
    "afternoon to Wednesday morning."

internal const val ELABORATE_TEXT = "The concert went well."

internal const val STYLE_TEXT = "Send me the report by noon."

internal const val EMOJIFY_TEXT = "Let's grab coffee and talk about the trip."

internal const val IMAGE_QUESTION = "What animal is in this picture? Answer in one short sentence."

internal val IMAGE_WORDS = listOf("cat", "kitten", "feline", "siamese")

internal fun catJpeg(): ByteArray = Base64.decode(CAT_JPEG_BASE64)

internal const val HUGGING_FACE = "https://huggingface.co"
internal const val UNREACHABLE_ENDPOINT = "https://no-such-host.invalid"
internal const val GEMMA_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
const val GEMMA_FILE_NAME = "gemma-4-E2B-it.litertlm"
internal const val EMBEDDING_REPO = "litert-community/embeddinggemma-2-text-270m-litert-lm"
internal const val EMBEDDING_FILE_NAME = "embeddinggemma-2-text-270m.litertlm"
