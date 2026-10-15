package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** sheet 中的一块区域：data 数据块（对应一张表）/ note 备注 / ignore 忽略 */
@Data
@TableName("dataset_block")
public class DatasetBlock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private String sheetName;
    private String rangeRef;
    private String kind;
    private String detectedBy;
    private String noteText;
}
