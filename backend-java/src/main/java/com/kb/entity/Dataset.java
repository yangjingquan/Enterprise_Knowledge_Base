package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 数据集（问数）：一个上传的 Excel / CSV 文件，挂在某个知识库下 */
@Data
@TableName("dataset")
public class Dataset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String name;
    private String fileName;
    private String filePath;
    /** csv / xlsx / xls */
    private String fileType;
    private String description;
    /** pending → parsing → awaiting_confirm → importing → ready / failed */
    private String status;
    private String errorMsg;
    private Integer tableCount;
    private Long rowCount;
    /** 是否允许把样例行发给大模型 */
    private Integer sendSamples;
    private Long userId;
    private LocalDateTime createTime;
}
