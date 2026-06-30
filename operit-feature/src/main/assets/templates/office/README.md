# Operit 办公文档工作区

这是一个专业的文档处理工作区，集成了强大的文档转换和排版工具。

## 核心工具

### � Pandoc - 文档格式转换利器
Pandoc 是最强大的文档格式转换工具，支持几十种格式互转。

#### 安装 Pandoc
```bash
# Linux/Termux
apt install pandoc

# 验证安装
pandoc --version
```

#### 常用转换命令
```bash
# Markdown → PDF（推荐使用 XeLaTeX 引擎）
pandoc input.md -o output.pdf --pdf-engine=xelatex -V CJKmainfont="Noto Sans CJK SC"

# Markdown → Word
pandoc input.md -o output.docx

# Markdown → HTML
pandoc input.md -o output.html --standalone

# Word → Markdown
pandoc input.docx -o output.md

# HTML → PDF
pandoc input.html -o output.pdf --pdf-engine=xelatex

# 批量转换 Markdown 为 PDF
for f in *.md; do pandoc "$f" -o "${f%.md}.pdf" --pdf-engine=xelatex; done
```

### 📐 XeLaTeX - 专业排版引擎
XeLaTeX 是支持 Unicode 和现代字体的 LaTeX 引擎，完美支持中文排版。

#### 安装 TeX Live（包含 XeLaTeX）
```bash
# Linux/Termux（精简安装）
apt install texlive-xetex texlive-fonts-recommended

# 完整安装（推荐，约 4GB）
apt install texlive-full
```

#### 直接编译 LaTeX
```bash
# 编译 .tex 文件为 PDF
xelatex document.tex

# 多次编译（用于目录和引用）
xelatex document.tex && xelatex document.tex
```

#### Pandoc + XeLaTeX 高级用法
```bash
# 使用自定义模板
pandoc input.md -o output.pdf --template=mytemplate.tex --pdf-engine=xelatex

# 添加目录
pandoc input.md -o output.pdf --toc --pdf-engine=xelatex

# 设置页边距和字体
pandoc input.md -o output.pdf --pdf-engine=xelatex \
  -V geometry:margin=2cm \
  -V CJKmainfont="Noto Sans CJK SC" \
  -V fontsize=12pt
```

## 其他实用工具

### 📊 文本处理工具
```bash
# wkhtmltopdf - HTML 转 PDF（另一种方案）
apt install wkhtmltopdf
wkhtmltopdf input.html output.pdf

# LibreOffice - Office 文档处理
apt install libreoffice
libreoffice --headless --convert-to pdf document.docx

# csvkit - CSV 数据处理
pip install csvkit
csvcut -c 1,3 data.csv > output.csv
csvsql --query "SELECT * FROM data WHERE value > 100" data.csv
```

### 🔍 文档搜索与处理
```bash
# 在多个文件中搜索内容
grep -r "关键词" .

# 使用 ripgrep（更快）
rg "关键词" --type md

# 批量重命名文件
rename 's/old/new/' *.txt

# PDF 文本提取
pdftotext document.pdf output.txt
```

### 📝 Markdown 增强
```bash
# markdown-toc - 自动生成目录
npm install -g markdown-toc
markdown-toc -i README.md

# prettier - 格式化 Markdown
npm install -g prettier
prettier --write *.md
```

## 推荐工作流

### 1. Markdown 写作 → PDF 发布
```bash
# 编写 Markdown 文档
vim report.md

# 转换为精美 PDF
pandoc report.md -o report.pdf \
  --pdf-engine=xelatex \
  --toc \
  -V CJKmainfont="Noto Sans CJK SC" \
  -V geometry:margin=2.5cm
```

### 2. 多格式文档转换
```bash
# 同时生成多种格式
pandoc document.md -o document.pdf --pdf-engine=xelatex
pandoc document.md -o document.docx
pandoc document.md -o document.html --standalone
```

### 3. LaTeX 学术排版
```bash
# 创建学术论文模板
cat > paper.tex << 'EOF'
\documentclass{article}
\usepackage{xeCJK}
\setCJKmainfont{Noto Sans CJK SC}
\title{我的论文}
\author{作者}
\begin{document}
\maketitle
\section{引言}
正文内容...
\end{document}
EOF

# 编译
xelatex paper.tex
```

## 文件组织建议

```
workspace/
├── source/         # 源文件（Markdown, LaTeX）
├── output/         # 输出文件（PDF, DOCX）
├── templates/      # 自定义模板
├── images/         # 图片资源
└── README.md
```

## 常见问题

### Q: 中文 PDF 显示为方框？
```bash
# 安装中文字体
apt install fonts-noto-cjk

# 在 pandoc 命令中指定字体
-V CJKmainfont="Noto Sans CJK SC"
```

### Q: 如何自定义 PDF 样式？
创建 YAML 元数据头部：
```yaml
---
title: "文档标题"
author: "作者名"
date: 2025-11-29
geometry: margin=2cm
fontsize: 12pt
---
```

### Q: 批量处理大量文档？
```bash
# Shell 脚本自动化
for file in source/*.md; do
    filename=$(basename "$file" .md)
    pandoc "$file" -o "output/${filename}.pdf" --pdf-engine=xelatex
done
```

## 提示

- 💡 优先使用 Pandoc + XeLaTeX 组合，生成高质量 PDF
- 💡 Markdown 是最佳的源文件格式，易编辑、易版本控制
- 💡 使用 Git 管理文档版本（已配置 .gitignore）
- 💡 复杂排版需求可直接编写 LaTeX
- 💡 批量处理建议使用 Shell 脚本自动化

Happy Writing! �✨
