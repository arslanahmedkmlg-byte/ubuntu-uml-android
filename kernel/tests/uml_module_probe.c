#include <linux/init.h>
#include <linux/module.h>

static int __init uml_module_probe_init(void)
{
	pr_info("uml_module_probe: loaded\n");
	return 0;
}

static void __exit uml_module_probe_exit(void)
{
	pr_info("uml_module_probe: unloaded\n");
}

module_init(uml_module_probe_init);
module_exit(uml_module_probe_exit);

MODULE_DESCRIPTION("Benign UML module loading acceptance probe");
MODULE_LICENSE("GPL");
