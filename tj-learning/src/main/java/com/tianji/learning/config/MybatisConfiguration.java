package com.tianji.learning.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TableNameHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.tianji.learning.utils.TableInfoContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class MybatisConfiguration {

    @Bean
    public DynamicTableNameInnerInterceptor dynamicTableNameInnerInterceptor(){
        Map<String, TableNameHandler> map = new HashMap<>(2);

        map.put("points_board", new TableNameHandler() {
            @Override
            public String dynamicTableName(String sql, String tableName) {
                return TableInfoContext.getInfo() == null ? tableName : TableInfoContext.getInfo();
            }
        });

        map.put("points_record", new TableNameHandler() {
            @Override
            public String dynamicTableName(String sql, String tableName) {
                return TableInfoContext.getInfo() == null ? tableName : TableInfoContext.getInfo();
            }
        });

        return new DynamicTableNameInnerInterceptor(map);
    }
}
