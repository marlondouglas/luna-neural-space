#!/usr/bin/env bash
# Liberação manual da assinatura do Rota GV (enquanto o Pix automático da Cora não está ativo).
#
# Uso:
#   tools/unlock.sh claims                    # lista os "Já paguei" pendentes
#   tools/unlock.sh lookup  <código|e-mail|celular|uid>
#   tools/unlock.sh grant   <código|...> [dias=30] [ref]   # soma dias ao "pago até"
#   tools/unlock.sh until   <código|...> <AAAA-MM-DD> [ref]
#   tools/unlock.sh revoke  <código|...>
#   tools/unlock.sh reset-devices <código|...>  # troca de celular (limite de aparelhos)
#
# O token de admin é lido de um ARQUIVO (nunca da linha de comando nem impresso):
#   ADMIN_SECRET_FILE (padrão: /workspace/secrets/license_admin_secret)
#   LUNA_SERVER       (padrão: https://luna-token-server.marlondouglas.workers.dev)
set -euo pipefail
SERVER="${LUNA_SERVER:-https://luna-token-server.marlondouglas.workers.dev}"
SECRET_FILE="${ADMIN_SECRET_FILE:-/workspace/secrets/license_admin_secret}"
[[ -r "$SECRET_FILE" ]] || { echo "Arquivo do token de admin não encontrado: $SECRET_FILE" >&2; exit 1; }

HDR="$(mktemp)"; chmod 600 "$HDR"; trap 'rm -f "$HDR"' EXIT
printf 'Authorization: Bearer %s\n' "$(tr -d '\r\n' < "$SECRET_FILE")" > "$HDR"

call() { # método caminho [json]
  if [[ $# -ge 3 ]]; then
    curl -sS -X "$1" -H @"$HDR" -H 'content-type: application/json' --data "$3" "$SERVER$2"
  else
    curl -sS -X "$1" -H @"$HDR" "$SERVER$2"
  fi
  echo
}
jstr() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

cmd="${1:-}"; shift || true
case "$cmd" in
  claims) call GET /admin/claims ;;
  lookup) call GET "/admin/lookup?q=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "${1:?código}")" ;;
  grant)  call POST /admin/grant "{\"q\":$(jstr "${1:?código}"),\"days\":${2:-30},\"ref\":$(jstr "${3:-manual}")}" ;;
  until)  call POST /admin/grant "{\"q\":$(jstr "${1:?código}"),\"until\":$(jstr "${2:?AAAA-MM-DD}"),\"ref\":$(jstr "${3:-manual}")}" ;;
  revoke) call POST /admin/revoke "{\"q\":$(jstr "${1:?código}")}" ;;
  reset-devices) call POST /admin/reset-devices "{\"q\":$(jstr "${1:?código}")}" ;;
  *) sed -n '2,13p' "$0"; exit 1 ;;
esac
