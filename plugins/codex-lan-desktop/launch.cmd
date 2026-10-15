@echo off
setlocal
if exist "%~dp0runtime\node.exe" (
  "%~dp0runtime\node.exe" "%~dp0desktop\mcp.mjs"
) else if defined CODEX_MCP_NODE_PATH (
  "%CODEX_MCP_NODE_PATH%" "%~dp0desktop\mcp.mjs"
) else (
  node "%~dp0desktop\mcp.mjs"
)
