package com.jzo2o.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jzo2o.ai.model.domain.AiChatRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI聊天记录 Mapper
 */
@Mapper
public interface AiChatRecordMapper extends BaseMapper<AiChatRecord> {
}
