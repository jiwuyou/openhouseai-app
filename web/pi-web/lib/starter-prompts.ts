export type StarterPrompt = {
  title: string;
  description: string;
  prompt: string;
};

export const STARTER_PROMPTS = [
  {
    title: "查看 OpenHouse 文档",
    description: "使用已安装的标准文档任务模板",
    prompt: "/openhouse-docs",
  },
  {
    title: "准备第二 AI 接力",
    description: "使用已安装的标准接力任务模板",
    prompt: "/openhouse-second-ai-handoff",
  },
  {
    title: "熟悉当前项目",
    description: "阅读项目结构、说明技术栈和主要入口",
    prompt: "请先阅读当前项目，说明它的目标、技术栈、主要模块、启动方式和关键入口。只做分析，不要修改文件。",
  },
  {
    title: "定位一个问题",
    description: "基于现象检查代码、日志和复现路径",
    prompt: "请检查当前项目中最可能影响正常运行的问题。先收集代码和日志证据，给出根因、影响范围和最小修复方案；在我确认前不要修改文件。",
  },
  {
    title: "实现一项改动",
    description: "理解仓库后完成实现并运行必要验证",
    prompt: "请先熟悉当前项目和已有约束，再询问我要实现的具体改动。收到目标后完成实现，并运行与风险相匹配的验证。不要回退已有改动。",
  },
  {
    title: "审查当前改动",
    description: "检查未提交改动的正确性与回归风险",
    prompt: "请审查当前项目的未提交改动，重点检查功能错误、回归风险、安全问题和缺失测试。先报告问题并按严重程度排序，不要直接修改文件。",
  },
] satisfies readonly StarterPrompt[];
