package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author shadow_maples
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final VoucherServiceImpl voucherService;
    private final

    @Override
    public Result seckillVoucher(Long voucherId) {
        VoucherOrder voucher = query().eq("voucher_id", voucherId).one();
        if (!Objects.isNull(voucher)) {
            query().gt("begin_time", voucher.get)

        }

        return null;
    }
}
