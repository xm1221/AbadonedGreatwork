package cn.xm1221.abadoned_greatwork.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import vazkii.patchouli.client.book.BookContentsBuilder;
import vazkii.patchouli.client.book.template.BookTemplate;
import vazkii.patchouli.common.book.Book;

import java.util.Map;
import java.util.function.Supplier;

@Mixin(value = BookContentsBuilder.class, remap = false)
public interface BookContentsBuilderAccessor {
    @Invoker("<init>")
    static BookContentsBuilder abw$create(Book book, boolean singleBookReload) {
        throw new AssertionError();
    }

    @Accessor("templates")
    Map<ResourceLocation, Supplier<BookTemplate>> abw$getTemplates();
}
