require_ubuntu

log "正在 Ubuntu 内执行 apt update"
run_ubuntu_logged bash -lc 'apt update'

log "正在 Ubuntu 内安装 curl、ca-certificates、git 和 procps"
run_ubuntu_logged bash -lc 'DEBIAN_FRONTEND=noninteractive apt install -y curl ca-certificates git procps'

log "Ubuntu 软件包阶段已完成。"
