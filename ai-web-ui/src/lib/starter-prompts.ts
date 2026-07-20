export type StarterPrompt = {
  title: string;
  description: string;
  prompt: string;
};

/** AI-only defaults. Product installation and maintenance prompts live outside this SPA. */
export const STARTER_PROMPTS = [
  {
    title: "整理今天的任务",
    description: "把零散想法变成清晰、可执行的计划",
    prompt: "请帮我整理今天的任务。先询问必要背景，再给出按优先级排列的可执行清单。",
  },
  {
    title: "阅读并修改文件",
    description: "让 Pi 在助手项目目录中完成真实工作",
    prompt: "请先查看当前项目目录，概括它的结构，并等我说明要修改的目标。",
  },
  {
    title: "解释一个概念",
    description: "用容易理解的方式逐步讲清楚",
    prompt: "我想理解一个概念。请先问我具体主题和已有基础，再用例子逐步解释。",
  },
  {
    title: "一起完成复杂任务",
    description: "规划、执行、检查，直到任务完成或确实需要输入",
    prompt: "我有一个复杂任务需要你完成。请先确认目标和约束，给出简短计划，然后持续执行和验证。",
  },
] satisfies readonly StarterPrompt[];
