package com.jzo2o.orders.history.mapper;

import com.jzo2o.orders.history.model.domain.HistoryOrders;
import com.jzo2o.orders.history.model.domain.HistoryOrdersSync;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jzo2o.orders.history.model.domain.StatDay;
import com.jzo2o.orders.history.model.domain.StatHour;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 订单统计
 * </p>
 *
 * @author itcast
 * @since 2023-09-21
 */
public interface HistoryOrdersSyncMapper extends BaseMapper<HistoryOrdersSync> {
    List<HistoryOrders> migrate(@Param("yesterDayStartTime") LocalDateTime yesterDayStartTime,
                                @Param("yesterDayEndTime") LocalDateTime yesterDayEndTime,
                                @Param("offset") Integer offset,
                                @Param("perNum") Integer perNum);

    List<StatHour> statForHour(@Param("queryDay") Integer queryDay);

    List<StatDay> statForDay(@Param("queryDay") Integer queryDay);

}
