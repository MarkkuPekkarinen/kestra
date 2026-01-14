<template>
    <el-card>
        <template #header>
            <div class="d-flex justify-content-between">
                <span class="d-inline-flex title align-items-center">
                    <AiIcon /><span>{{ $t("ai.flow.title") }}</span>
                </span>
                <el-button
                    class="border-0 ai-close-button"
                    size="small"
                    :icon="Close"
                    @click.stop="emit('close')"
                />
            </div>
        </template>

        <el-input
            autosize
            ref="promptInput"
            v-if="configured"
            type="textarea"
            :placeholder="$t('ai.flow.prompt_placeholder')"
            v-model="prompt"
            @keydown.exact.ctrl.enter="$event.preventDefault(); prompt += '\n'"
            @keydown.exact.enter.prevent="submitPrompt"
            class="ai-copilot-placeholder"
        />
        <template v-else>
            <!-- eslint-disable-next-line vue/no-v-text-v-html-on-component -->
            <el-text class="keep-whitespace" v-html="$t('ai.flow.enable_instructions.header')" />
            <div class="mt-2" v-html="highlightedAiConfiguration"/>
            <!-- eslint-disable-next-line vue/no-v-text-v-html-on-component -->
            <el-text class="keep-whitespace" v-html="$t('ai.flow.enable_instructions.footer')" />
        </template>
        <template #footer>
            <div class="d-flex justify-content-between">
                <div class="d-flex align-items-center gap-2">
                    <el-button
                        v-if="speechSupported"
                        class="ai-mic-button"
                        :loading="isListening"
                        @click="toggleVoiceInput"
                        :aria-pressed="isListening"
                        title="Speech to prompt"
                    >
                        🎤
                    </el-button>
                    <el-text class="text-tertiary" size="small">
                        (⌘) Ctrl + Alt (⌥) + Shift + K {{ $t("to toggle") }}
                    </el-text>
                </div>

                <div v-if="configured" class="d-flex flex-column align-items-end gap-3">
                    <el-text v-if="error !== undefined" type="danger" size="default" class="me-auto">
                        {{ error }}
                    </el-text>

                    <div v-if="waitingForReply" class="d-flex loading-text">
                        <div v-loading="true" />
                        <span>{{ $t('ai.flow.generating') }}</span>
                    </div>

                    <el-button
                        v-else
                        type="primary"
                        :icon="KeyboardReturn"
                        :disabled="prompt.length === 0"
                        @click="submitPrompt"
                    >
                        {{ $t('submit') }}
                    </el-button>
                </div>
            </div>
        </template>
    </el-card>
</template>

<script setup lang="ts">
    import {computed, nextTick, onMounted, onUnmounted, ref, watch} from "vue";
    import Close from "vue-material-design-icons/Close.vue";
    import KeyboardReturn from "vue-material-design-icons/KeyboardReturn.vue";
    import AiIcon from "./AiIcon.vue";
    import {useAiStore} from "../../stores/ai";
    import Utils from "../../utils/utils";
    import {useMiscStore} from "override/stores/misc";
    import type {InputInstance} from "element-plus";

    const aiStore = useAiStore();
    const emit = defineEmits<{
        close: [];
        generatedYaml: [string];
    }>();

    const promptInput = ref<InputInstance>();
    const prompt = ref(sessionStorage.getItem("kestra-ai-prompt") ?? "");
    const waitingForReply = ref(false);

    const speechSupported = ref(false);
    const isListening = ref(false);
    const speechRecognition = ref<any | null>(null);

    const basePrompt = ref("");
    const lastSpoken = ref("");
    const internalWrite = ref(false);

    const props = defineProps<{
        flow: string;
        conversationId: string;
    }>();

    const error = ref<string | undefined>(undefined);

    function focusPrompt() {
        promptInput.value?.focus?.();
        promptInput.value?.textarea?.focus?.();
    }

    function safeSetPrompt(value: string) {
        internalWrite.value = true;
        prompt.value = value;
        // release guard on next tick (so watcher sees internalWrite=true for this mutation)
        nextTick(() => {
            internalWrite.value = false;
        });
    }

    function resetBuffersFromCurrentPrompt() {
        basePrompt.value = (prompt.value ?? "").trim();
        lastSpoken.value = "";
    }

    async function submitPrompt() {
        error.value = undefined;
        waitingForReply.value = true;

        try {
            const aiResponse = (await aiStore.generateFlow({
                userPrompt: prompt.value,
                flowYaml: props.flow,
                conversationId: props.conversationId,
            })) as string;

            emit("generatedYaml", aiResponse);
        } catch (e: any) {
            error.value = (e.response?.data?.message as string) ?? e;
        } finally {
            waitingForReply.value = false;
        }
    }

    const highlightedAiConfiguration = ref<string | undefined>();

    const miscStore = useMiscStore();
    const configured = computed(() => miscStore.configs?.isAiEnabled);

    /**
     * Persist prompt
     */
    watch(prompt, (newValue) => {
        sessionStorage.setItem("kestra-ai-prompt", newValue);
    });

    /**
     * When the user clears/edits the prompt (Ctrl+A + Del) while NOT listening,
     * we must ensure next session starts from a clean base.
     *
     * We do it unconditionally on any user edit (internalWrite=false),
     * regardless of isListening state.
     */
    watch(
        prompt,
        (newValue) => {
            // Ignore changes we made ourselves from SpeechRecognition
            if (internalWrite.value) return;

            // Any user edit => sync buffers to match current input state
            basePrompt.value = (newValue ?? "").trim();
            lastSpoken.value = "";
        },
        {flush: "sync"}
    );

    onMounted(() => {
        focusPrompt();
    });

    onUnmounted(() => {
        sessionStorage.removeItem("kestra-ai-prompt");
        if (speechRecognition.value) {
            try {
                speechRecognition.value.abort();
            } catch {
                // ignore
            }
        }
    });

    /**
     * Setup speech recognition once.
     */
    onMounted(async () => {
        const SpeechRecognitionConstructor =
            (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

        speechSupported.value = Boolean(SpeechRecognitionConstructor);
        if (!SpeechRecognitionConstructor) return;

        const recognition = new SpeechRecognitionConstructor();
        recognition.lang = navigator.language ?? "en-US";
        recognition.continuous = false;
        recognition.interimResults = true;
        recognition.maxAlternatives = 1;

        recognition.onend = () => {
            isListening.value = false;

            // Keep prompt as last set, but clear session transcript buffer
            lastSpoken.value = "";
        };

        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        recognition.onerror = (e: any) => {
            isListening.value = false;
            lastSpoken.value = "";
        };

        recognition.onresult = (event: any) => {
            let interim = "";
            let finalText = "";

            for (let i = event.resultIndex; i < event.results.length; i++) {
                const res = event.results[i];
                const txt = (res?.[0]?.transcript ?? "").toString();
                if (res?.isFinal) finalText += txt;
                else interim += txt;
            }

            const spoken = (finalText || interim).trim();
            if (!spoken) return;

            lastSpoken.value = spoken;

            const nextValue = basePrompt.value
                ? `${basePrompt.value} ${lastSpoken.value}`.trim()
                : lastSpoken.value;

            safeSetPrompt(nextValue);
            focusPrompt();
        };

        speechRecognition.value = recognition;
    });

    /**
     * Chrome quirk guard:
     * Sometimes calling start right after stop/end can be flaky.
     * We do: abort() -> small delay -> start()
     */
    function startRecognitionSafely(recognition: any) {
        try {
            // Force clean state (helps after previous session)
            recognition.abort?.();
        } catch {
            // ignore
        }

        setTimeout(() => {
            try {
                recognition.start();
            } catch (e) {
                console.error(e);
                isListening.value = false;
            }
        }, 80);
    }

    function toggleVoiceInput() {
        const recognition = speechRecognition.value;
        if (!recognition) return;

        if (isListening.value) {
            try {
                recognition.stop();
            } catch (e) {
                console.error(e);
            }
            return;
        }

        // Snapshot current input every time you start (covers: cleared input, edited input, etc)
        resetBuffersFromCurrentPrompt();

        isListening.value = true;
        startRecognitionSafely(recognition);
    }

    onMounted(async () => {
        if (!configured.value) {
            const {createHighlighterCore, langs, githubDark, githubLight, onigurumaEngine} =
                await import("../../utils/markdownDeps");

            const highlighter = await createHighlighterCore({
                langs: [langs.yaml],
                themes: [githubDark, githubLight],
                engine: onigurumaEngine,
            });

            highlightedAiConfiguration.value = highlighter.codeToHtml(
                `kestra:
                        ai:
                          type: "gemini"
                          gemini:
                            api-key: "geminiApiKey"
                            model-name: gemini-2.5-flash`,
                {lang: "yaml", theme: Utils.getTheme() === "dark" ? "github-dark" : "github-light"}
            );
        }
    });
</script>

<style scoped lang="scss">
    :deep(.el-card__header) {
        font-size: 12px;
        line-height: 1;
        border-bottom: none;

        .title :not(:first-child) {
            margin-left: 6px;
        }
    }

    :deep(.el-card__footer) {
        border-top: none;
    }

    .loading-text {
        :first-child {
            width: 20px;
            height: 20px;
            --el-loading-spinner-size: 20px;
        }

        :not(:first-child) {
            margin-left: 6px;
        }
    }

    .ai-copilot-placeholder :deep(textarea::placeholder) {
        color: gray;
        font-style: italic;
    }

    // Enhanced close button animation
    .ai-close-button {
        transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);

        &:hover {
            transform: translateY(-2px);
            opacity: 0.8;
        }

        &:active {
            transform: translateY(0);
            opacity: 0.6;
        }
    }

    // Staggered animations for children elements (scaleX only, faster)
    :deep(.el-card__header) {
        animation: scaleInX 0.30s cubic-bezier(0.2, 0.8, 0.2, 1) 0.04s both;
    }

    :deep(.el-card__body) {
        animation: scaleInX 0.30s cubic-bezier(0.2, 0.8, 0.2, 1) 0.08s both;
    }

    :deep(.el-card__footer) {
        animation: scaleInX 0.30s cubic-bezier(0.2, 0.8, 0.2, 1) 0.12s both;
    }

    @keyframes scaleInX {
        from {
            opacity: 0;
            transform: scaleX(0.85);
        }
        to {
            opacity: 1;
            transform: scaleX(1);
        }
    }
</style>
