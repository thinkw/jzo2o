"""工具定义 — 本地工具(Python直接执行)和远程工具(Java通过WebSocket执行)"""
from langchain_core.tools import tool

# =============================================================================
# 本地工具 — Python 直接执行
# =============================================================================


@tool
async def calculate(expression: str) -> str:
    """执行数学计算。输入一个数学表达式字符串, 返回计算结果。支持加减乘除、幂运算、括号等"""
    try:
        # 安全的 eval: 仅允许数学运算符和数字, 禁止所有内置函数
        result = eval(expression, {"__builtins__": {}}, {})
        return str(result)
    except Exception as e:
        return f"计算错误: {e}"


@tool
async def get_current_time() -> str:
    """获取当前日期和时间"""
    from datetime import datetime
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


@tool
async def search_web(query: str) -> str:
    """联网搜索, 获取最新的网络信息。当需要了解实时新闻、最新资讯、或不确定的实时信息时使用。"""
    import logging
    logger = logging.getLogger(__name__)

    try:
        from tavily import TavilyClient
        from app.core.config import settings

        api_key = settings.tavily_api_key
        if not api_key:
            return ("错误: 未配置 TAVILY_API_KEY 环境变量。"
                    "请在 .env 文件中添加 TAVILY_API_KEY=你的API密钥。"
                    "免费获取: https://app.tavily.com")

        client = TavilyClient(api_key=api_key)
        response = client.search(
            query=query,
            search_depth="basic",
            include_answer=True,
            max_results=5,
        )

        # 组装结果: 答案 + 来源
        parts = []
        if response.get("answer"):
            parts.append(f"## 搜索结果摘要\n{response['answer']}")

        results = response.get("results", [])
        if results:
            parts.append("\n## 相关来源")
            for r in results:
                title = r.get("title", "无标题")
                url = r.get("url", "")
                content = r.get("content", "")[:300]
                parts.append(f"- **{title}**\n  {content}\n  {url}")

        if not parts:
            return "未找到相关的搜索结果。"

        return "\n\n".join(parts)

    except ImportError:
        return ("错误: tavily-python 未安装。请运行 pip install tavily-python")
    except Exception as e:
        logger.error("Tavily 搜索失败: %s", str(e))
        return f"搜索失败: {e}"


# =============================================================================
# 远程工具 — Java 通过 WebSocket 执行 (存根仅供 schema 生成)
# =============================================================================


@tool
async def customer_order_query(order_id: str) -> str:
    """查询客户订单详情。根据订单ID查询完整订单信息，包括订单状态、金额、服务类型、服务人员、地址等。"""
    raise NotImplementedError("Remote tool executed via Java WebSocket")


@tool
async def get_evaluation_summary(target_type_id: int, target_id: int) -> str:
    """读取上次 AI 评价总结。返回旧总结内容和上次总结时间, 用于增量合并。"""
    raise NotImplementedError("Remote tool executed via Java WebSocket")


@tool
async def query_evaluations(target_type_id: int, target_id: int, after_time: str = "") -> str:
    """查询指定目标在指定时间之后的新增评价列表。after_time 为空时查询所有评价。"""
    raise NotImplementedError("Remote tool executed via Java WebSocket")


# =============================================================================
# 工具注册表
# =============================================================================

LOCAL_TOOLS: list = [calculate, get_current_time, search_web]
REMOTE_TOOLS: list = [customer_order_query, get_evaluation_summary, query_evaluations]
ALL_TOOLS: list = LOCAL_TOOLS + REMOTE_TOOLS

# 远程工具名集合, tool_executor 节点用于路由判断
REMOTE_TOOL_NAMES: set = {t.name for t in REMOTE_TOOLS}

# 本地工具名到工具对象的映射, tool_executor 节点用于快速查找
local_tools_by_name: dict = {t.name: t for t in LOCAL_TOOLS}
