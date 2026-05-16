"""配置管理 — 从环境变量读取，兼容后续 Nacos 配置中心接入"""
import os
from dotenv import load_dotenv

load_dotenv()


class Settings:
    """全局配置，环境变量 > .env 文件 > 默认值"""
    # LLM 配置
    llm_api_base: str = os.getenv("LLM_API_BASE", "https://api.openai.com/v1")
    llm_api_key: str = os.getenv("LLM_API_KEY", "")
    llm_model: str = os.getenv("LLM_MODEL", "gpt-4o-mini")
    llm_temperature: float = float(os.getenv("LLM_TEMPERATURE", "0.0"))
    llm_max_tokens: int = int(os.getenv("LLM_MAX_TOKENS", "4096"))

    # Tavily 联网搜索
    tavily_api_key: str = os.getenv("TAVILY_API_KEY", "")

    # 工具配置
    tool_timeout: int = int(os.getenv("TOOL_TIMEOUT", "120"))

    # 检查点持久化 (SQLite, 进程重启后恢复对话状态)
    checkpoint_db_path: str = os.getenv("CHECKPOINT_DB_PATH",
                                         "data/checkpoints.db")

    # 服务配置
    server_host: str = os.getenv("SERVER_HOST", "0.0.0.0")
    server_port: int = int(os.getenv("SERVER_PORT", "8000"))

    # Nacos 配置 (后续启用)
    nacos_server_addr: str = os.getenv("NACOS_SERVER_ADDR", "localhost:8848")
    nacos_namespace: str = os.getenv("NACOS_NAMESPACE", "")
    nacos_service_name: str = os.getenv("NACOS_SERVICE_NAME", "jzo2o-ai-engine")
    nacos_group: str = os.getenv("NACOS_GROUP", "DEFAULT_GROUP")


settings = Settings()
