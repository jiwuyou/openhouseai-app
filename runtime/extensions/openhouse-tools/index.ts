type ToolResult = {
	content: Array<{ type: "text"; text: string }>;
	details: Record<string, unknown>;
	isError?: boolean;
};

type ExecResult = {
	stdout?: string;
	stderr?: string;
	code?: number;
	killed?: boolean;
};

const MAX_CODE_BYTES = 64 * 1024;
const MAX_COMMAND_BYTES = 64 * 1024;
const DEFAULT_TIMEOUT_MS = 30_000;
const MAX_TIMEOUT_MS = 300_000;

function errorText(error: unknown): string {
	if (error instanceof Error) return error.message;
	return String(error);
}

function toolError(message: string, details: Record<string, unknown> = {}): ToolResult {
	return {
		content: [{ type: "text", text: message }],
		details,
		isError: true,
	};
}

function toolSuccess(message: string, details: Record<string, unknown> = {}): ToolResult {
	return {
		content: [{ type: "text", text: message }],
		details,
	};
}

function timeoutFrom(value: unknown): number | ToolResult {
	if (value === undefined) return DEFAULT_TIMEOUT_MS;
	if (!Number.isInteger(value) || Number(value) < 1 || Number(value) > MAX_TIMEOUT_MS) {
		return toolError(`timeoutMs must be an integer between 1 and ${MAX_TIMEOUT_MS}`);
	}
	return Number(value);
}

function executionText(result: ExecResult): string {
	const sections = [`exitCode: ${result.code ?? -1}`];
	if (result.stdout) sections.push(`stdout:\n${result.stdout}`);
	if (result.stderr) sections.push(`stderr:\n${result.stderr}`);
	if (result.killed) sections.push("process was killed");
	return sections.join("\n\n");
}

async function runIndependentProcess(
	pi: any,
	command: string,
	args: string[],
	options: { cwd?: string; timeout: number; signal?: AbortSignal },
): Promise<ToolResult> {
	try {
		// Every invocation goes directly through a new pi.exec hostcall. No executor,
		// process handle, promise, stdout buffer, or cancellation state is shared.
		const result = (await pi.exec(command, args, options)) as ExecResult;
		const details = {
			command,
			exitCode: result.code ?? -1,
			stdout: result.stdout ?? "",
			stderr: result.stderr ?? "",
			killed: result.killed ?? false,
		};
		if ((result.code ?? -1) !== 0) {
			return toolError(executionText(result), details);
		}
		return toolSuccess(executionText(result), details);
	} catch (error) {
		// Missing runtimes, spawn failures, cancellation, and timeout are normal tool
		// failures. Returning isError keeps the result inside Pi's agent loop.
		return toolError(`${command} failed to start or complete: ${errorText(error)}`, {
			command,
			spawnError: errorText(error),
		});
	}
}

function shellQuote(value: string): string {
	return `'${value.replace(/'/g, `'"'"'`)}'`;
}

async function callAndroidBridge(
	pi: any,
	toolCallId: string,
	toolName: string,
	parameters: Record<string, unknown>,
): Promise<ToolResult> {
	try {
		const request = JSON.stringify({ id: toolCallId, arguments: parameters });
		const response = (await pi.exec(
			"sh",
			[
				"-c",
				'exec sh "$OPENHOUSE_ANDROID_BRIDGE_HELPER" "$@"',
				"openhouse-bridge",
				toolName,
				request,
			],
			{ timeout: 65_000 },
		)) as ExecResult;
		if ((response.code ?? -1) !== 0) {
			return toolError(
				`Android ${toolName} bridge process failed: ${response.stderr || `exit ${response.code ?? -1}`}`,
				{ exitCode: response.code ?? -1 },
			);
		}
		const raw = response.stdout ?? "";
		const separator = raw.lastIndexOf("\n");
		const status = separator >= 0 ? Number(raw.slice(separator + 1).trim()) : 0;
		const body = separator >= 0 ? raw.slice(0, separator) : raw;
		let payload: any = body;
		if (typeof payload === "string") {
			try {
				payload = JSON.parse(payload);
			} catch {
				payload = { text: payload };
			}
		}
		const content = typeof payload?.content === "object" && payload.content !== null
			? payload.content
			: {};
		const errorMessage = typeof payload?.error?.message === "string"
			? payload.error.message
			: `Android ${toolName} failed`;
		if (status < 200 || status >= 300 || payload?.isError === true) {
			return toolError(errorMessage, {
				status,
				callId: payload?.callId ?? toolCallId,
				content,
				error: payload?.error ?? null,
			});
		}
		return toolSuccess(JSON.stringify(content), {
			status,
			callId: payload?.callId ?? toolCallId,
			content,
		});
	} catch (error) {
		return toolError(`Android ${toolName} bridge call failed: ${errorText(error)}`, {
			bridgeError: errorText(error),
		});
	}
}

function registerAndroidTool(
	pi: any,
	name: string,
	label: string,
	description: string,
	parameters: Record<string, unknown>,
): void {
	pi.registerTool({
		name,
		label,
		description,
		parameters,
		execute: async (toolCallId: string, params: Record<string, unknown>) =>
			callAndroidBridge(pi, toolCallId, name, params),
	});
}

export default function openHouseTools(pi: any): void {
	pi.registerTool({
		name: "code_runner",
		label: "Code Runner",
		description:
			"Run one Bash, Python, or Node.js snippet in a fresh subprocess. Missing runtimes and non-zero exits are returned as tool errors.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["language", "code"],
			properties: {
				language: { type: "string", enum: ["bash", "python", "node"] },
				code: { type: "string", minLength: 1, maxLength: MAX_CODE_BYTES },
				cwd: { type: "string", minLength: 1 },
				timeoutMs: { type: "integer", minimum: 1, maximum: MAX_TIMEOUT_MS },
			},
		},
		execute: async (
			_toolCallId: string,
			params: { language: string; code: string; cwd?: string; timeoutMs?: number },
			signal: AbortSignal,
		): Promise<ToolResult> => {
			if (new TextEncoder().encode(params.code).length > MAX_CODE_BYTES) {
				return toolError(`code exceeds ${MAX_CODE_BYTES} UTF-8 bytes`);
			}
			const timeout = timeoutFrom(params.timeoutMs);
			if (typeof timeout !== "number") return timeout;
			const runtime = {
				bash: { command: "bash", args: ["-c", params.code] },
				python: { command: "python3", args: ["-c", params.code] },
				node: { command: "node", args: ["-e", params.code] },
			}[params.language];
			if (!runtime) return toolError(`unsupported language: ${params.language}`);
			return runIndependentProcess(pi, runtime.command, runtime.args, {
				cwd: params.cwd,
				timeout,
				signal,
			});
		},
	});

	pi.registerTool({
		name: "ubuntu_exec",
		label: "Ubuntu Exec",
		description:
			"Run a command inside a proot-distro Ubuntu installation. A missing proot-distro or Ubuntu installation is returned immediately as a tool error.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["command"],
			properties: {
				command: { type: "string", minLength: 1, maxLength: MAX_COMMAND_BYTES },
				cwd: { type: "string", minLength: 1 },
				distribution: { type: "string", pattern: "^[A-Za-z0-9._-]+$", default: "ubuntu" },
				timeoutMs: { type: "integer", minimum: 1, maximum: MAX_TIMEOUT_MS },
			},
		},
		execute: async (
			_toolCallId: string,
			params: { command: string; cwd?: string; distribution?: string; timeoutMs?: number },
			signal: AbortSignal,
		): Promise<ToolResult> => {
			if (new TextEncoder().encode(params.command).length > MAX_COMMAND_BYTES) {
				return toolError(`command exceeds ${MAX_COMMAND_BYTES} UTF-8 bytes`);
			}
			const timeout = timeoutFrom(params.timeoutMs);
			if (typeof timeout !== "number") return timeout;
			const distribution = params.distribution ?? "ubuntu";
			if (!/^[A-Za-z0-9._-]+$/.test(distribution)) {
				return toolError("distribution contains unsupported characters");
			}
			const command = params.cwd
				? `cd -- ${shellQuote(params.cwd)} && ${params.command}`
				: params.command;
			return runIndependentProcess(
				pi,
				"proot-distro",
				["login", distribution, "--", "sh", "-lc", command],
				{ timeout, signal },
			);
		},
	});

	registerAndroidTool(pi, "clipboard", "Clipboard", "Read or write the Android clipboard.", {
		type: "object",
		additionalProperties: false,
		required: ["operation"],
		properties: {
			operation: { type: "string", enum: ["read", "write"] },
			text: { type: "string" },
		},
	});
	registerAndroidTool(pi, "intent", "Android Intent", "Launch an Android intent through WuxianPi.", {
		type: "object",
		additionalProperties: false,
		required: ["action"],
		properties: {
			action: { type: "string" },
			data: { type: "string" },
			mimeType: { type: "string" },
			package: { type: "string" },
			extras: { type: "object" },
		},
	});
	registerAndroidTool(pi, "share", "Share", "Open the Android share sheet with text.", {
		type: "object",
		additionalProperties: false,
		required: ["text"],
		properties: {
			text: { type: "string", minLength: 1 },
			mimeType: { type: "string" },
			subject: { type: "string" },
			title: { type: "string" },
		},
	});
	registerAndroidTool(pi, "notification", "Notification", "Post an Android notification.", {
		type: "object",
		additionalProperties: false,
		required: ["title", "text"],
		properties: {
			title: { type: "string", minLength: 1 },
			text: { type: "string", minLength: 1 },
			id: { type: "integer" },
		},
	});
}
