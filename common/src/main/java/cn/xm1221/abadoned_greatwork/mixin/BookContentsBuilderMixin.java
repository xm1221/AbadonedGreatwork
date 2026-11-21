package cn.xm1221.abadoned_greatwork.mixin;

import cn.xm1221.abadoned_greatwork.client.notes.patchouli.UserNoteBookInjector;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vazkii.patchouli.client.book.BookCategory;
import vazkii.patchouli.client.book.BookContentsBuilder;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.template.BookTemplate;
import vazkii.patchouli.common.book.Book;

import java.util.Map;
import java.util.function.Supplier;

@Mixin(value = BookContentsBuilder.class, remap = false)
public abstract class BookContentsBuilderMixin {
    @Shadow
    @Final
    private Book book;

    @Shadow
    @Final
    private Map<ResourceLocation, BookCategory> categories;

    @Shadow
    @Final
    private Map<ResourceLocation, BookEntry> entries;

    @Shadow
    @Final
    private Map<ResourceLocation, Supplier<BookTemplate>> templates;

    @Inject(method = "loadFiles", at = @At("TAIL"))
    private void abw$injectUserNotes(CallbackInfo ci) {
        UserNoteBookInjector.inject(this.book, this.categories, this.entries, this.templates);
    }
}
