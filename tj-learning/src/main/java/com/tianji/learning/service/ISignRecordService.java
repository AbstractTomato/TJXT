package com.tianji.learning.service;

import com.tianji.learning.domain.vo.SignResultVO;

public interface ISignRecordService {
    //签到功能的接口
    public SignResultVO addSignRecords();

    //返回本月的签到结果
    Byte[] querySignRecords();

}
