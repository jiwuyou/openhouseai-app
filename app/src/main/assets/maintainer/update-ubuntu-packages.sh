require_ubuntu

log "正在 Ubuntu 内测速并选择 apt 镜像源"
run_ubuntu_logged bash -lc 'set -euo pipefail
. /etc/os-release
codename="${VERSION_CODENAME:-noble}"
if [ -n "${OPENHOUSEAI_UBUNTU_APT_MIRROR:-}" ]; then
  selected_mirror="$OPENHOUSEAI_UBUNTU_APT_MIRROR"
  echo "使用指定 Ubuntu apt 镜像源：$selected_mirror"
else
  selected_mirror=""
  best_time=""
  candidates="
https://mirrors.ustc.edu.cn/ubuntu-ports
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
http://ports.ubuntu.com/ubuntu-ports
"
  for mirror in $candidates; do
    probe_url="$mirror/dists/$codename/InRelease"
    metrics="$(curl -fsSL --connect-timeout 5 --max-time 12 -o /dev/null -w "%{time_total} %{http_code}" "$probe_url" 2>/dev/null || true)"
    probe_time="${metrics%% *}"
    http_code="${metrics##* }"
    if [ "$http_code" = "200" ] && [ -n "$probe_time" ]; then
      echo "Ubuntu apt 镜像测速：$mirror ${probe_time}s"
      if [ -z "$best_time" ] || awk "BEGIN{exit !($probe_time < $best_time)}"; then
        best_time="$probe_time"
        selected_mirror="$mirror"
      fi
    else
      echo "Ubuntu apt 镜像不可用：$mirror"
    fi
  done
fi
if [ -z "${selected_mirror:-}" ]; then
  echo "未找到可用 Ubuntu apt 镜像源，保留系统默认源。"
  exit 0
fi
echo "选择 Ubuntu apt 镜像源：$selected_mirror"
mkdir -p /etc/apt/sources.list.d /etc/apt/openhouseai-backup
if [ -f /etc/apt/sources.list ]; then
  mv /etc/apt/sources.list /etc/apt/openhouseai-backup/sources.list.bak 2>/dev/null || true
fi
if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
  mv /etc/apt/sources.list.d/ubuntu.sources /etc/apt/openhouseai-backup/ubuntu.sources.bak 2>/dev/null || true
fi
cat > /etc/apt/sources.list.d/openhouseai-ubuntu.sources <<EOF
Types: deb
URIs: $selected_mirror
Suites: $codename $codename-updates $codename-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: $selected_mirror
Suites: $codename-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF'

log "正在 Ubuntu 内执行 apt update"
run_ubuntu_logged bash -lc 'apt update'

log "正在 Ubuntu 内安装 curl、ca-certificates、git、procps、ripgrep 和 unzip"
run_ubuntu_logged bash -lc 'DEBIAN_FRONTEND=noninteractive apt install -y curl ca-certificates git procps ripgrep unzip'

log "Ubuntu 软件包阶段已完成。"
