#!/data/data/com.termux/files/usr/bin/bash

if [ "${SMALLPHONEAI_UBUNTU_MIRROR_POLICY_LOADED:-0}" = "1" ] \
  && declare -F smallphoneai_resolve_ubuntu_rootfs_url >/dev/null 2>&1; then
  return 0 2>/dev/null || exit 0
fi
SMALLPHONEAI_UBUNTU_MIRROR_POLICY_LOADED=1

smallphoneai_mirror_policy_error() {
  printf '[SmallPhoneAI] Ubuntu mirror policy: %s\n' "$*" >&2
}

smallphoneai_resolve_dual_namespace_value() {
  local openhouse_name="$1" smallphone_name="$2" label="$3"
  local openhouse_value="${!openhouse_name:-}" smallphone_value="${!smallphone_name:-}"

  if [ -n "$openhouse_value" ] && [ -n "$smallphone_value" ] \
    && [ "$openhouse_value" != "$smallphone_value" ]; then
    smallphoneai_mirror_policy_error \
      "$label 配置冲突：$openhouse_name=$openhouse_value，$smallphone_name=$smallphone_value"
    return 64
  fi

  if [ -n "$openhouse_value" ]; then
    printf '%s\n' "$openhouse_value"
  else
    printf '%s\n' "$smallphone_value"
  fi
}

smallphoneai_network_profile_path() {
  local configured
  configured="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_TERMUX_MIRROR_PROFILE \
    SMALLPHONEAI_TERMUX_MIRROR_PROFILE \
    'Termux mirror profile path' 2>/dev/null || true)"
  printf '%s\n' "${configured:-${HOME:-/data/data/com.termux/files/home}/.local/state/wuxianpi-setup/mirror/profile.json}"
}

smallphoneai_network_class() {
  local profile raw
  profile="$(smallphoneai_network_profile_path)"
  [ -s "$profile" ] || {
    printf 'legacy\n'
    return 0
  }
  if grep -Eq '"schema"[[:space:]]*:[[:space:]]*3' "$profile" \
    && grep -Eq '"validated"[[:space:]]*:[[:space:]]*true' "$profile"; then
    raw="$(sed -n 's/.*"networkClass"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$profile" | head -n 1)"
  fi
  case "$raw" in
    cn|global) printf '%s\n' "$raw" ;;
    *) printf 'legacy\n' ;;
  esac
}

smallphoneai_log_network_class() {
  local profile class
  profile="$(smallphoneai_network_profile_path)"
  class="$(smallphoneai_network_class)"
  if [ "$class" = legacy ]; then
    smallphoneai_mirror_policy_error "未找到有效 schema:3 网络 profile，使用兼容候选顺序"
  else
    smallphoneai_mirror_policy_error "读取首次测速网络 profile：$class（$profile）"
  fi
}

smallphoneai_ubuntu_mirror_run_id() {
  local run_id safe_run_id
  run_id="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID \
    SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID \
    'run-id')" || return $?
  if [ -z "$run_id" ]; then
    run_id="${OPENHOUSE_RUN_STARTED_AT_MS:-${SMALLPHONEAI_RUN_STARTED_AT_MS:-$(date +%s 2>/dev/null || printf 0)-$$}}"
  fi
  safe_run_id="$(printf '%s' "$run_id" | tr -c 'A-Za-z0-9._-' '_')"
  [ -n "$safe_run_id" ] || safe_run_id="run-$$"
  export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="$safe_run_id"
  export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="$safe_run_id"
  printf '%s\n' "$safe_run_id"
}

smallphoneai_ubuntu_mirror_lock_root() {
  local lock_root
  lock_root="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT \
    SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT \
    'lock root')" || return $?
  if [ -z "$lock_root" ]; then
    lock_root="${TMPDIR:-/tmp}/smallphoneai-ubuntu-mirror-locks"
  fi
  export OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="$lock_root"
  export SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="$lock_root"
  printf '%s\n' "$lock_root"
}

smallphoneai_acquire_ubuntu_mirror_lock() {
  local kind="$1" run_id lock_root lock_dir owner_file wait_seconds deadline owner_pid now
  run_id="$(smallphoneai_ubuntu_mirror_run_id)" || return $?
  lock_root="$(smallphoneai_ubuntu_mirror_lock_root)" || return $?
  export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="$run_id"
  export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="$run_id"
  export OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="$lock_root"
  export SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="$lock_root"
  wait_seconds="${SMALLPHONEAI_UBUNTU_MIRROR_LOCK_WAIT_SECONDS:-40}"
  case "$wait_seconds" in
    ''|*[!0-9]*) wait_seconds=40 ;;
  esac
  mkdir -p "$lock_root" || return 1
  lock_dir="$lock_root/$run_id.$kind.lock"
  owner_file="$lock_dir/pid"
  deadline=$(( $(date +%s 2>/dev/null || printf 0) + wait_seconds ))

  while ! mkdir "$lock_dir" 2>/dev/null; do
    owner_pid="$(sed -n '1p' "$owner_file" 2>/dev/null || true)"
    if [ -n "$owner_pid" ] && ! kill -0 "$owner_pid" 2>/dev/null; then
      rm -f "$owner_file" 2>/dev/null || true
      rmdir "$lock_dir" 2>/dev/null || true
      continue
    fi
    now="$(date +%s 2>/dev/null || printf 0)"
    if [ "$now" -ge "$deadline" ]; then
      smallphoneai_mirror_policy_error "等待 $kind mirror lock 超时：$lock_dir"
      return 75
    fi
    sleep 1
  done

  printf '%s\n' "$$" > "$owner_file"
  SMALLPHONEAI_UBUNTU_MIRROR_HELD_LOCK="$lock_dir"
  export SMALLPHONEAI_UBUNTU_MIRROR_HELD_LOCK
}

smallphoneai_release_ubuntu_mirror_lock() {
  local lock_dir="${1:-${SMALLPHONEAI_UBUNTU_MIRROR_HELD_LOCK:-}}"
  [ -n "$lock_dir" ] || return 0
  rm -f "$lock_dir/pid" 2>/dev/null || true
  rmdir "$lock_dir" 2>/dev/null || true
  if [ "${SMALLPHONEAI_UBUNTU_MIRROR_HELD_LOCK:-}" = "$lock_dir" ]; then
    unset SMALLPHONEAI_UBUNTU_MIRROR_HELD_LOCK
  fi
}

smallphoneai_classify_mirror_failure() {
  local curl_exit="${1:-1}" http_code="${2:-000}"
  case "$http_code" in
    408|425|429|5??) printf 'transient\n'; return 0 ;;
    4??) printf 'permanent\n'; return 0 ;;
    2??|3??)
      if [ "$curl_exit" = "0" ]; then
        printf 'success\n'
      else
        printf 'transient\n'
      fi
      return 0
      ;;
  esac

  case "$curl_exit" in
    5|6|7|18|23|26|27|28|35|47|52|55|56|81|89|92|95|97)
      printf 'transient\n'
      ;;
    *)
      printf 'permanent\n'
      ;;
  esac
}

smallphoneai_ubuntu_rootfs_arch() {
  local machine="${1:-${SMALLPHONEAI_UBUNTU_ROOTFS_ARCH:-${OPENHOUSEAI_UBUNTU_ROOTFS_ARCH:-$(uname -m)}}}"
  case "$machine" in
    aarch64|arm64) printf 'arm64\n' ;;
    x86_64|amd64) printf 'amd64\n' ;;
    arm|armv7l|armv8l|armhf) printf 'armhf\n' ;;
    *)
      smallphoneai_mirror_policy_error "不支持的 Ubuntu rootfs 架构：$machine"
      return 65
      ;;
  esac
}

smallphoneai_ubuntu_rootfs_candidates() {
  local arch policy
  arch="$(smallphoneai_ubuntu_rootfs_arch "${1:-}")" || return $?
  policy="$(smallphoneai_network_class)"
  case "$policy" in
    global)
      cat <<EOF
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
EOF
      return 0
      ;;
    cn)
      cat <<EOF
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
EOF
      return 0
      ;;
  esac
  cat <<EOF
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz
EOF
}

smallphoneai_normalize_ubuntu_mirror_candidates() {
  local label="$1" candidate seen=""
  while IFS= read -r candidate; do
    candidate="${candidate%$'\r'}"
    [ -n "$candidate" ] || continue
    case "$candidate" in
      http://*|https://*) ;;
      *)
        smallphoneai_mirror_policy_error "$label 候选 URL 无效：$candidate"
        return 64
        ;;
    esac
    case "$candidate" in
      *[[:space:]]*)
        smallphoneai_mirror_policy_error "$label 候选 URL 包含空白字符：$candidate"
        return 64
        ;;
    esac
    case "$seen" in
      *"|$candidate|"*) continue ;;
    esac
    seen="${seen}|${candidate}|"
    printf '%s\n' "$candidate"
  done
}

smallphoneai_ubuntu_rootfs_list_override() {
  local candidates
  candidates="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_ROOTFS_URLS SMALLPHONEAI_UBUNTU_ROOTFS_URLS \
    'Ubuntu rootfs candidate list')" || return $?
  [ -n "$candidates" ] || return 0
  printf '%s\n' "$candidates" \
    | smallphoneai_normalize_ubuntu_mirror_candidates 'Ubuntu rootfs'
}

smallphoneai_ubuntu_rootfs_effective_candidates() {
  local arch="${1:-}" single candidates
  single="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_ROOTFS_URL SMALLPHONEAI_UBUNTU_ROOTFS_URL \
    'Ubuntu rootfs override')" || return $?
  if [ -n "$single" ]; then
    printf '%s\n' "$single" \
      | smallphoneai_normalize_ubuntu_mirror_candidates 'Ubuntu rootfs override'
    return $?
  fi
  candidates="$(smallphoneai_ubuntu_rootfs_list_override)" || return $?
  if [ -n "$candidates" ]; then
    printf '%s\n' "$candidates"
    return 0
  fi
  smallphoneai_ubuntu_rootfs_candidates "$arch"
}

smallphoneai_ubuntu_apt_candidates() {
  case "$(smallphoneai_network_class)" in
    global)
      cat <<'EOF'
https://ports.ubuntu.com/ubuntu-ports
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
https://mirrors.ustc.edu.cn/ubuntu-ports
EOF
      return 0
      ;;
    cn)
      cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
https://mirrors.ustc.edu.cn/ubuntu-ports
https://ports.ubuntu.com/ubuntu-ports
EOF
      return 0
      ;;
  esac
  cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
http://ports.ubuntu.com/ubuntu-ports
https://mirrors.ustc.edu.cn/ubuntu-ports
EOF
}

smallphoneai_mirror_timeout_value() {
  local phase="$1" openhouse_name smallphone_name default_value value
  case "$phase" in
    first)
      openhouse_name=OPENHOUSEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS
      smallphone_name=SMALLPHONEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS
      default_value=16
      ;;
    retry)
      openhouse_name=OPENHOUSEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS
      smallphone_name=SMALLPHONEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS
      default_value=32
      ;;
    *) return 64 ;;
  esac
  value="$(smallphoneai_resolve_dual_namespace_value "$openhouse_name" "$smallphone_name" "$phase timeout")" || return $?
  [ -n "$value" ] || value="$default_value"
  case "$value" in
    ''|*[!0-9]*)
      smallphoneai_mirror_policy_error "$phase timeout 不是正整数：$value"
      return 64
      ;;
  esac
  [ "$value" -gt 0 ] || return 64
  printf '%s\n' "$value"
}

smallphoneai_probe_ubuntu_rootfs_mirror() {
  local url="$1" timeout_seconds="$2" metrics curl_exit http_code size_download classification speed bytes seconds
  local headers content_range temp_root max_filesize configured_max_filesize
  temp_root="${TMPDIR:-/tmp}"
  configured_max_filesize="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_ROOTFS_PROBE_MAX_BYTES \
    SMALLPHONEAI_UBUNTU_ROOTFS_PROBE_MAX_BYTES \
    'rootfs probe max bytes')" || return $?
  max_filesize="${configured_max_filesize:-2097152}"
  case "$max_filesize" in
    ''|*[!0-9]*)
      smallphoneai_mirror_policy_error "rootfs probe max bytes 无效：$max_filesize"
      return 64
      ;;
  esac
  [ "$max_filesize" -ge 1 ] || return 64
  [ "$max_filesize" -ge 1048576 ] || max_filesize=2097152
  mkdir -p "$temp_root" || return 1
  headers="$(mktemp "$temp_root/smallphoneai-rootfs-range.XXXXXX")" || return 1
  metrics="$(curl --silent --show-error --location --fail-with-body \
    --connect-timeout "$timeout_seconds" --max-time "$timeout_seconds" \
    --range 0-1048575 --max-filesize "$max_filesize" \
    --dump-header "$headers" --output /dev/null \
    --write-out '%{http_code} %{size_download} %{time_total}' "$url" 2>/dev/null)"
  curl_exit=$?
  http_code="${metrics%% *}"
  metrics="${metrics#* }"
  size_download="${metrics%% *}"
  seconds="${metrics##* }"
  bytes="$size_download"
  speed="$(awk -v bytes="$bytes" -v seconds="$seconds" 'BEGIN { if (seconds > 0) printf "%.0f", bytes / seconds; else print 0 }')"
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_SPEED_BPS="${speed:-0}"
  content_range="$(tr -d '\r' < "$headers" | awk '
    tolower($1)=="content-range:" {
      $1=""
      sub(/^[[:space:]]+/, "")
      value=$0
    }
    END { print value }
  ')"
  rm -f "$headers" 2>/dev/null || true
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_EXIT="$curl_exit"
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_HTTP="${http_code:-000}"
  case "$http_code" in
    200)
      SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS=permanent
      return 1
      ;;
    206)
      if [ "$curl_exit" = "0" ] && [ "${size_download:-0}" -eq 1048576 ] 2>/dev/null; then
        case "$content_range" in
          'bytes 0-1048575/'*)
            SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS=success
            return 0
            ;;
        esac
      fi
      case "$content_range:$size_download" in
        'bytes 0-1048575/'*:1048576) ;;
        *)
          SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS=permanent
          return 1
          ;;
      esac
      ;;
  esac
  classification="$(smallphoneai_classify_mirror_failure "$curl_exit" "${http_code:-000}")"
  if [ "$classification" = "success" ]; then
    classification=permanent
  fi
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS="$classification"
  return 1
}

smallphoneai_probe_ubuntu_apt_mirror() {
  local mirror="$1" codename="$2" timeout_seconds="$3" metrics curl_exit http_code classification speed bytes seconds
  metrics="$(curl --silent --show-error --location --fail-with-body \
    --connect-timeout "$timeout_seconds" --max-time "$timeout_seconds" \
    --output /dev/null --write-out '%{http_code} %{size_download} %{time_total}' \
    "$mirror/dists/$codename/InRelease" 2>/dev/null)"
  curl_exit=$?
  http_code="${metrics%% *}"
  metrics="${metrics#* }"
  bytes="${metrics%% *}"
  seconds="${metrics##* }"
  speed="$(awk -v bytes="$bytes" -v seconds="$seconds" 'BEGIN { if (seconds > 0) printf "%.0f", bytes / seconds; else print 0 }')"
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_SPEED_BPS="${speed:-0}"
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_EXIT="$curl_exit"
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_HTTP="${http_code:-000}"
  if [ "$curl_exit" = "0" ] && [ "$http_code" = "200" ]; then
    SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS=success
    return 0
  fi
  classification="$(smallphoneai_classify_mirror_failure "$curl_exit" "${http_code:-000}")"
  if [ "$classification" = "success" ]; then
    classification=permanent
  fi
  SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS="$classification"
  return 1
}

smallphoneai_export_resolved_ubuntu_mirror() {
  local kind="$1" value="$2"
  case "$kind" in
    rootfs)
      export OPENHOUSEAI_UBUNTU_ROOTFS_URL="$value"
      export SMALLPHONEAI_UBUNTU_ROOTFS_URL="$value"
      export OPENHOUSEAI_RESOLVED_UBUNTU_ROOTFS_URL="$value"
      export SMALLPHONEAI_RESOLVED_UBUNTU_ROOTFS_URL="$value"
      ;;
    apt)
      export OPENHOUSEAI_UBUNTU_APT_MIRROR="$value"
      export SMALLPHONEAI_UBUNTU_APT_MIRROR="$value"
      export OPENHOUSEAI_RESOLVED_UBUNTU_APT_MIRROR="$value"
      export SMALLPHONEAI_RESOLVED_UBUNTU_APT_MIRROR="$value"
      ;;
    *) return 64 ;;
  esac
}

smallphoneai_resolve_ubuntu_mirror() {
  local kind="$1" detail="$2" override first_timeout retry_timeout lock_dir candidate selected=""
  local transient_candidates="" result_file run_id lock_root candidates selection_variant=default policy ranked_file

  case "$kind" in
    rootfs)
      override="$(smallphoneai_resolve_dual_namespace_value \
        OPENHOUSEAI_UBUNTU_ROOTFS_URL SMALLPHONEAI_UBUNTU_ROOTFS_URL \
        'Ubuntu rootfs override')" || return $?
      ;;
    apt)
      override="$(smallphoneai_resolve_dual_namespace_value \
        OPENHOUSEAI_UBUNTU_APT_MIRROR SMALLPHONEAI_UBUNTU_APT_MIRROR \
        'Ubuntu apt override')" || return $?
      ;;
    *) return 64 ;;
  esac
  if [ -n "$override" ]; then
    smallphoneai_export_resolved_ubuntu_mirror "$kind" "$override"
    printf '%s\n' "$override"
    return 0
  fi

  policy="$(smallphoneai_network_class)"
  smallphoneai_log_network_class
  selection_variant="$policy"

  if [ "$kind" = rootfs ]; then
    candidates="$(smallphoneai_ubuntu_rootfs_list_override)" || return $?
    if [ -n "$candidates" ]; then
      selection_variant="$policy-list-$(printf '%s\n' "$candidates" | cksum | awk '{print $1 "-" $2}')"
    fi
  fi

  first_timeout="$(smallphoneai_mirror_timeout_value first)" || return $?
  retry_timeout="$(smallphoneai_mirror_timeout_value retry)" || return $?
  smallphoneai_acquire_ubuntu_mirror_lock "$kind-$detail-$selection_variant" || return $?
  lock_dir="$SMALLPHONEAI_UBUNTU_MIRROR_HELD_LOCK"
  run_id="$(smallphoneai_ubuntu_mirror_run_id)" || {
    smallphoneai_release_ubuntu_mirror_lock "$lock_dir"
    return 1
  }
  lock_root="$(smallphoneai_ubuntu_mirror_lock_root)" || {
    smallphoneai_release_ubuntu_mirror_lock "$lock_dir"
    return 1
  }
  export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="$run_id"
  export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="$run_id"
  export OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="$lock_root"
  export SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="$lock_root"
  result_file="$lock_root/$run_id.$kind-$detail-$selection_variant.selected"
  ranked_file="$lock_root/$run_id.$kind-$detail-$selection_variant.ranked"
  if [ -s "$result_file" ]; then
    selected="$(sed -n '1p' "$result_file")"
  fi

  if [ -z "$selected" ]; then
    if [ "$kind" = rootfs ]; then
      if [ -z "$candidates" ]; then
        candidates="$(smallphoneai_ubuntu_rootfs_candidates "$detail")" || {
          smallphoneai_release_ubuntu_mirror_lock "$lock_dir"
          return 1
        }
      fi
    else
      candidates="$(smallphoneai_ubuntu_apt_candidates)"
    fi

    : > "$ranked_file"
    while IFS= read -r candidate; do
      [ -n "$candidate" ] || continue
      if { [ "$kind" = rootfs ] \
          && smallphoneai_probe_ubuntu_rootfs_mirror "$candidate" "$first_timeout"; } \
        || { [ "$kind" = apt ] \
          && smallphoneai_probe_ubuntu_apt_mirror "$candidate" "$detail" "$first_timeout"; }; then
        if [ "$policy" = cn ]; then
          printf '%s\t%s\n' "$candidate" "${SMALLPHONEAI_UBUNTU_MIRROR_LAST_SPEED_BPS:-0}" >> "$ranked_file"
        else
          selected="$candidate"
          break
        fi
      elif [ "${SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS:-permanent}" = transient ]; then
        transient_candidates="${transient_candidates}${candidate}\n"
      fi
    done <<< "$candidates"
    if [ "$policy" = cn ] && [ -s "$ranked_file" ]; then
      selected="$(sort -t $'\t' -k2,2nr "$ranked_file" | head -n 1 | cut -f1)"
    fi

    if [ -n "$transient_candidates" ] \
      && { [ "$policy" = cn ] || [ -z "$selected" ]; }; then
      while IFS= read -r candidate; do
        [ -n "$candidate" ] || continue
        if { [ "$kind" = rootfs ] \
          && smallphoneai_probe_ubuntu_rootfs_mirror "$candidate" "$retry_timeout"; } \
          || { [ "$kind" = apt ] \
          && smallphoneai_probe_ubuntu_apt_mirror "$candidate" "$detail" "$retry_timeout"; }; then
          if [ "$policy" = cn ]; then
            printf '%s\t%s\n' "$candidate" "${SMALLPHONEAI_UBUNTU_MIRROR_LAST_SPEED_BPS:-0}" >> "$ranked_file"
          elif [ -z "$selected" ]; then
            selected="$candidate"
            break
          fi
        fi
      done <<< "$(printf '%b' "$transient_candidates")"
      if [ "$policy" = cn ] && [ -s "$ranked_file" ]; then
        selected="$(sort -t $'\t' -k2,2nr "$ranked_file" | head -n 1 | cut -f1)"
      fi
    fi

    if [ -n "$selected" ]; then
      if ! printf '%s\n' "$selected" > "$result_file"; then
        smallphoneai_release_ubuntu_mirror_lock "$lock_dir"
        smallphoneai_mirror_policy_error "无法写入 mirror selection cache：$result_file"
        return 1
      fi
    fi
  fi

  smallphoneai_release_ubuntu_mirror_lock "$lock_dir"
  if [ -z "$selected" ]; then
    smallphoneai_mirror_policy_error "没有可用的 Ubuntu $kind mirror"
    return 69
  fi
  smallphoneai_export_resolved_ubuntu_mirror "$kind" "$selected"
  printf '%s\n' "$selected"
}

smallphoneai_resolve_ubuntu_rootfs_url() {
  local arch
  arch="$(smallphoneai_ubuntu_rootfs_arch "${1:-}")" || return $?
  smallphoneai_resolve_ubuntu_mirror rootfs "$arch"
}

smallphoneai_resolve_ubuntu_apt_mirror() {
  local codename="${1:-noble}"
  case "$codename" in
    ''|*[!A-Za-z0-9._-]*)
      smallphoneai_mirror_policy_error "Ubuntu codename 无效：$codename"
      return 64
      ;;
  esac
  smallphoneai_resolve_ubuntu_mirror apt "$codename"
}

smallphoneai_write_canonical_ubuntu_sources() {
  local mirror="$1" codename="${2:-noble}" apt_root target backup_dir target_dir tmp lock_dir
  local configured_root configured_target configured_backup
  [ -n "$mirror" ] || return 64
  case "$mirror" in *[[:space:]]*) return 64 ;; esac
  case "$codename" in ''|*[!A-Za-z0-9._-]*) return 64 ;; esac

  configured_root="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_APT_ROOT SMALLPHONEAI_UBUNTU_APT_ROOT \
    'Ubuntu apt root')" || return $?
  apt_root="${configured_root:-/etc/apt}"
  configured_target="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_SOURCES_FILE SMALLPHONEAI_UBUNTU_SOURCES_FILE \
    'Ubuntu sources file')" || return $?
  target="${configured_target:-$apt_root/sources.list.d/openhouseai-ubuntu.sources}"
  configured_backup="$(smallphoneai_resolve_dual_namespace_value \
    OPENHOUSEAI_UBUNTU_SOURCES_BACKUP_DIR SMALLPHONEAI_UBUNTU_SOURCES_BACKUP_DIR \
    'Ubuntu sources backup dir')" || return $?
  backup_dir="${configured_backup:-$apt_root/openhouseai-backup}"
  target_dir="$(dirname "$target")"
  mkdir -p "$target_dir" "$backup_dir" || return 1
  smallphoneai_acquire_ubuntu_mirror_lock sources || return $?
  lock_dir="$SMALLPHONEAI_UBUNTU_MIRROR_HELD_LOCK"

  if [ -f "$apt_root/sources.list" ]; then
    if [ ! -e "$backup_dir/sources.list.bak" ]; then
      mv "$apt_root/sources.list" "$backup_dir/sources.list.bak" 2>/dev/null || true
    else
      rm -f "$apt_root/sources.list" 2>/dev/null || true
    fi
  fi
  if [ -f "$apt_root/sources.list.d/ubuntu.sources" ]; then
    if [ ! -e "$backup_dir/ubuntu.sources.bak" ]; then
      mv "$apt_root/sources.list.d/ubuntu.sources" "$backup_dir/ubuntu.sources.bak" 2>/dev/null || true
    else
      rm -f "$apt_root/sources.list.d/ubuntu.sources" 2>/dev/null || true
    fi
  fi
  if [ "$target" != "$apt_root/sources.list.d/smallphoneai-ubuntu.sources" ] \
    && [ -f "$apt_root/sources.list.d/smallphoneai-ubuntu.sources" ]; then
    if [ ! -e "$backup_dir/smallphoneai-ubuntu.sources.bak" ]; then
      mv "$apt_root/sources.list.d/smallphoneai-ubuntu.sources" \
        "$backup_dir/smallphoneai-ubuntu.sources.bak" 2>/dev/null || true
    else
      rm -f "$apt_root/sources.list.d/smallphoneai-ubuntu.sources" 2>/dev/null || true
    fi
  fi
  if [ -f "$target" ] && [ ! -e "$backup_dir/openhouseai-ubuntu.sources.bak" ]; then
    cp "$target" "$backup_dir/openhouseai-ubuntu.sources.bak" 2>/dev/null || true
  fi

  tmp="$target.tmp.$$"
  if ! cat > "$tmp" <<EOF
Types: deb
URIs: $mirror
Suites: $codename $codename-updates $codename-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: $mirror
Suites: $codename-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
  then
    smallphoneai_release_ubuntu_mirror_lock "$lock_dir"
    return 1
  fi
  chmod 644 "$tmp" 2>/dev/null || true
  if ! mv "$tmp" "$target"; then
    rm -f "$tmp" 2>/dev/null || true
    smallphoneai_release_ubuntu_mirror_lock "$lock_dir"
    return 1
  fi
  smallphoneai_release_ubuntu_mirror_lock "$lock_dir"

  export OPENHOUSEAI_UBUNTU_APT_MIRROR="$mirror"
  export SMALLPHONEAI_UBUNTU_APT_MIRROR="$mirror"
  export OPENHOUSEAI_RESOLVED_UBUNTU_APT_MIRROR="$mirror"
  export SMALLPHONEAI_RESOLVED_UBUNTU_APT_MIRROR="$mirror"
  printf '%s\n' "$target"
}
