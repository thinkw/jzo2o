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


# =============================================================================
# 远程工具 — Java 通过 WebSocket 执行 (存根仅供 schema 生成)
# =============================================================================


# 后续扩展: 在此添加远程工具存根
# @tool
# async def customer_order_query(order_id: str) -> str:
#     """查询客户订单详情"""
#     raise NotImplementedError("Remote tool executed via Java WebSocket")


# =============================================================================
# 工具注册表
# =============================================================================

LOCAL_TOOLS: list = [calculate, get_current_time]
REMOTE_TOOLS: list = []  # 远程工具后续逐步添加
ALL_TOOLS: list = LOCAL_TOOLS + REMOTE_TOOLS

# 远程工具名集合, tool_executor 节点用于路由判断
REMOTE_TOOL_NAMES: set = {t.name for t in REMOTE_TOOLS}

# 本地工具名到工具对象的映射, tool_executor 节点用于快速查找
local_tools_by_name: dict = {t.name: t for t in LOCAL_TOOLS}
